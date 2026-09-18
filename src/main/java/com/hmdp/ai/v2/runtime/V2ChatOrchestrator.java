package com.hmdp.ai.v2.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.*;
import com.hmdp.ai.entity.AiWorkingMemory;
import com.hmdp.ai.runtime.ConversationEventType;
import com.hmdp.ai.service.ChatMemoryService;
import com.hmdp.ai.service.ConversationEventService;
import com.hmdp.ai.service.VersionConflictException;
import com.hmdp.ai.service.WorkingMemoryVersionService;
import com.hmdp.ai.v2.grounding.*;
import com.hmdp.ai.v2.plan.*;
import com.hmdp.ai.v2.reducer.*;
import com.hmdp.ai.v2.semantic.TaskDirective;
import com.hmdp.ai.v2.semantic.TurnSemantics;
import com.hmdp.ai.v2.verification.DeterministicResultVerifier;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/** The V2 turn boundary: PRE semantics are committed once; execution and POST have a separate retry policy. */
@Service
public class V2ChatOrchestrator {
    @Resource private SemanticInterpreter semanticInterpreter;
    @Resource private WorkingMemoryVersionService versions;
    @Resource private ChatMemoryService chatMemoryService;
    @Resource private ConversationEventService conversationEventService;
    @Resource private ObjectMapper objectMapper;
    @Resource private StaticPlanExecutor executor;
    @Resource private V2ActionHandler actions;
    @Resource private V2ResponseRenderer renderer;
    @Resource private ShopMapper shopMapper;
    @Resource private V2LocationResolver locationResolver;

    private final TaskLifecycleReducer lifecycleReducer = new TaskLifecycleReducer();
    private final PreExecutionReducer preReducer = new PreExecutionReducer();
    private final PostExecutionReducer postReducer = new PostExecutionReducer();
    private final V2TaskStateGateway stateGateway = new V2TaskStateGateway();
    private final EffectiveTaskContextResolver taskContextResolver = new EffectiveTaskContextResolver();
    private final GroundingResolver groundingResolver = new GroundingResolver();
    private final ExecutionPlanCompiler compiler = new ExecutionPlanCompiler();
    private final DeterministicResultVerifier verifier = new DeterministicResultVerifier();

    public ChatMessageResponse chat(ChatMessageRequest request) {
        if (request == null || request.getMessage() == null || request.getMessage().isBlank())
            throw new IllegalArgumentException("message cannot be blank");
        String chatId = chatMemoryService.resolveChatId(request.getChatId());
        request.setChatId(chatId);
        String message = request.getMessage().trim();
        List<Map<String, Object>> history = chatMemoryService.load(chatId);
        conversationEventService.begin(chatId, (history == null ? 0 : history.size()) / 2 + 1);
        try {
            TurnSemantics semantics = semanticInterpreter.interpret(message);
            conversationEventService.recordBestEffort(ConversationEventType.SEMANTIC_INTERPRETATION,
                    com.hmdp.ai.runtime.ConversationEventStatus.SUCCESS, null, null, preEvent(semantics), null);

            PreOutcome pre = prePhase(request, chatId, semantics);
            ChatMessageResponse response;
            if (pre.response() != null) {
                response = pre.response();
            } else {
                response = postPhase(chatId, pre.prepared());
            }
            chatMemoryService.appendTurn(chatId, request.getMessage(), response.getAnswer(), response.getRoute(), null);
            return response;
        } finally {
            // V2 状态事件依赖 chat/turn trace 关联工作记忆版本；不能落入 synthetic system trace。
            conversationEventService.clearTrace();
        }
    }

    /** PRE conflict reloads and regrounds at most once. No POST path calls this method again. */
    private PreOutcome prePhase(ChatMessageRequest request, String chatId, TurnSemantics semantics) {
        for (int attempt = 0; attempt < 2; attempt++) {
            VersionedMemory loaded = load(chatId);
            PreBuild built = preparePre(request, loaded.memory(), semantics);
            if (built.response() != null && !built.persistBeforeResponse()) return new PreOutcome(null, built.response());
            try {
                AiWorkingMemory committed = versions.append(chatId, userId(), loaded.version(), loaded.memory(),
                        ConversationEventType.STATE_REDUCED, preEvent(semantics),
                        Map.of("taskId", built.taskId()));
                if (built.response() != null) return new PreOutcome(null, built.response());
                if (built.abandon()) return new PreOutcome(null, renderer.render(chatId,
                        ResponseSpec.completed("当前推荐任务已结束。")));
                PlanningSnapshot snapshot = snapshot(built.task(), loaded.memory(), committed.getVersion());
                return new PreOutcome(new PreparedTurn(loaded.memory(), committed.getVersion(), built.taskId(),
                        loaded.memory().getActiveTaskId(), semantics, built.grounded(), snapshot), null);
            } catch (VersionConflictException conflict) {
                if (attempt == 1) return new PreOutcome(null, retryResponse(chatId));
            }
        }
        return new PreOutcome(null, retryResponse(chatId));
    }

    private PreBuild preparePre(ChatMessageRequest request, ConversationWorkingMemory memory, TurnSemantics semantics) {
        List<TaskView> views = taskViews(memory);
        EffectiveTaskContextResult contextResult = taskContextResolver.resolveResult(memory.getActiveTaskId(), views, semantics);
        if (contextResult instanceof EffectiveTaskContextResult.NeedsClarification clarification)
            return PreBuild.response(clarification(chatId(request), "请说明你指的是哪一个推荐任务：" + clarification.ambiguity().detail()));

        EffectiveTaskContext context = ((EffectiveTaskContextResult.Resolved) contextResult).context();
        String restoreTaskId = context.task() == null ? null : context.task().taskId();
        TaskDirective directive = semantics.taskDirective();
        if (memory.activeTask() == null && directive == TaskDirective.CONTINUE) directive = TaskDirective.START_NEW;
        final DecisionTaskState task;
        try {
            task = lifecycleReducer.apply(memory, directive, restoreTaskId);
        } catch (IllegalArgumentException invalidLifecycle) {
            return PreBuild.response(clarification(chatId(request), "无法按当前任务状态继续，请重新说明要继续、恢复或结束哪个任务。"));
        }
        if (directive == TaskDirective.ABANDON) return PreBuild.abandon(task.getTaskId());

        EffectiveTaskContext currentContext = new EffectiveTaskContext(view(task, memory.getTasks().indexOf(task)),
                context.restoredForThisTurn());
        GroundingResult grounding = groundingResolver.resolve(semantics, currentContext);
        if (grounding instanceof GroundingResult.NeedsClarification clarification)
            return PreBuild.response(clarification(chatId(request), "请明确是哪一家或哪一批推荐：" + clarification.ambiguities().getFirst().detail()));
        GroundedTurn grounded = ((GroundingResult.Grounded) grounding).turn();

        V2TaskState reduced = preReducer.reduce(stateGateway.read(task), semantics, grounded);
        V2LocationResolver.Resolution location = locationResolver.resolve(reduced.criteria(), request.getLocation(), reduced.searchAnchor(),
                hasLocationMutation(semantics));
        if (location instanceof V2LocationResolver.Resolution.NeedsClarification clarification) {
            // Requirements and grounded feedback are deterministic user facts at this point.  A missing
            // search anchor only blocks resolution/execution; it must not roll those facts back.
            stateGateway.write(task, reduced);
            return PreBuild.persistedResponse(task.getTaskId(), clarification(chatId(request), clarification.message()));
        }
        reduced = reduced.withSearchAnchor(((V2LocationResolver.Resolution.Resolved) location).anchor());
        stateGateway.write(task, reduced);
        return PreBuild.ready(task.getTaskId(), task, grounded);
    }

    /** POST may reapply only POST effects. On a causal conflict it recompiles/reexecutes once from latest state. */
    private ChatMessageResponse postPhase(String chatId, PreparedTurn prepared) {
        PlanRun firstRun = compileExecuteVerify(prepared.grounded(), prepared.snapshot());
        try {
            DecisionTaskState task = applyPost(prepared.preMemory(), prepared.taskId(), firstRun.execution());
            versions.append(chatId, userId(), prepared.preVersion(), prepared.preMemory(), ConversationEventType.STATE_REDUCED,
                    postEvent(prepared, firstRun, "V2_POST", false), Map.of("taskId", prepared.taskId()));
            return response(chatId, task, firstRun.execution());
        } catch (VersionConflictException postConflict) {
            VersionedMemory latest = load(chatId);
            DecisionTaskState latestTask = findTask(latest.memory(), prepared.taskId());
            if (latestTask == null || latestTask.getV2Lifecycle() == TaskLifecycle.ABANDONED)
                return retryResponse(chatId);
            PlanningSnapshot latestSnapshot = snapshot(latestTask, latest.memory(), latest.version());
            boolean inputsUnchanged = Objects.equals(prepared.activeTaskId(), latest.memory().getActiveTaskId())
                    && sameCausalInputs(prepared.snapshot(), latestSnapshot);
            PlanRun chosenRun = inputsUnchanged ? firstRun : compileExecuteVerify(prepared.grounded(), latestSnapshot);
            try {
                DecisionTaskState task = applyPost(latest.memory(), prepared.taskId(), chosenRun.execution());
                versions.append(chatId, userId(), latest.version(), latest.memory(), ConversationEventType.STATE_REDUCED,
                        postEvent(prepared, chosenRun, "V2_POST_RETRY", !inputsUnchanged),
                        Map.of("taskId", prepared.taskId(), "replanned", !inputsUnchanged));
                return response(chatId, task, chosenRun.execution());
            } catch (VersionConflictException retryConflict) {
                // A candidate-producing result is never rendered unless its post-state commit won.
                return retryResponse(chatId);
            }
        }
    }

    private PlanRun compileExecuteVerify(GroundedTurn grounded, PlanningSnapshot snapshot) {
        try {
            CompilationResult compilation = compiler.compile(grounded, snapshot);
            Map<String, SearchSpec> searches = searchSpecs(compilation);
            StaticPlanExecutor.ExecutionResult execution = execute(compilation);
            VerifiedExecution verified = verify(execution, searches);
            return new PlanRun(verified.execution(), searches, verified.failures(), verified.status());
        } catch (RuntimeException failure) {
            ExecutionObservation observation = new ExecutionObservation("v2-runtime", ExecutionObservation.Status.FAILURE,
                    List.of(), null, List.of(), null, "本轮计划无法安全执行，请调整条件后重试。");
            return new PlanRun(new StaticPlanExecutor.ExecutionResult(Map.of(), List.of(observation)), Map.of(), List.of(),
                    com.hmdp.ai.evaluation.VerificationStatus.NOT_EXECUTED);
        }
    }

    public StaticPlanExecutor.ExecutionResult execute(CompilationResult compilation) {
        if (compilation instanceof CompilationResult.StaticPlan staticPlan)
            return executor.execute(staticPlan.plan(), actions);
        if (compilation instanceof CompilationResult.Direct direct) {
            ExecutionObservation observation = actions.execute(direct.action());
            return new StaticPlanExecutor.ExecutionResult(Map.of(), List.of(observation));
        }
        return new StaticPlanExecutor.ExecutionResult(Map.of(), List.of(new ExecutionObservation("adaptive",
                ExecutionObservation.Status.UNSUPPORTED, List.of(), null, List.of(), null,
                "目前暂不支持自适应研究。")));
    }

    private Map<String, SearchSpec> searchSpecs(CompilationResult compilation) {
        List<ExecutionAction> actions = new ArrayList<>();
        if (compilation instanceof CompilationResult.Direct direct) actions.add(direct.action());
        if (compilation instanceof CompilationResult.StaticPlan plan)
            plan.plan().groups().forEach(group -> actions.addAll(group.actions()));
        return actions.stream().filter(ExecutionAction.SearchAction.class::isInstance)
                .map(ExecutionAction.SearchAction.class::cast)
                .collect(Collectors.toUnmodifiableMap(ExecutionAction.SearchAction::requestId,
                        ExecutionAction.SearchAction::spec, (first, ignored) -> first));
    }

    private VerifiedExecution verify(StaticPlanExecutor.ExecutionResult execution, Map<String, SearchSpec> searches) {
        List<ExecutionObservation> observations = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        boolean verificationAttempted = false;
        for (ExecutionObservation observation : execution.observations()) {
            SearchSpec spec = searches.get(observation.requestId());
            if (spec == null || observation.recommendations().isEmpty()) {
                if (!observation.candidateShopIds().isEmpty() && spec == null) {
                    observations.add(new ExecutionObservation(observation.requestId(), ExecutionObservation.Status.EMPTY,
                            List.of(), observation.evidence(), List.of(), observation.generalAnswer(),
                            "候选缺少可验证的检索契约。", observation.value()));
                } else observations.add(observation);
                continue;
            }
            verificationAttempted = true;
            var report = verifier.verify(spec, observation.recommendations());
            failures.addAll(report.failedHardChecks());
            List<DecisionRecommendation> safe = report.verifiedCandidates();
            ExecutionObservation.Status status = safe.isEmpty() ? ExecutionObservation.Status.EMPTY : ExecutionObservation.Status.SUCCESS;
            observations.add(new ExecutionObservation(observation.requestId(), status,
                    safe.stream().map(DecisionRecommendation::getShopId).filter(Objects::nonNull).toList(),
                    observation.evidence(), observation.effects(), observation.generalAnswer(), observation.detail(),
                    observation.value(), safe));
        }
        com.hmdp.ai.evaluation.VerificationStatus status = searches.isEmpty()
                ? com.hmdp.ai.evaluation.VerificationStatus.NOT_APPLICABLE
                : !verificationAttempted ? com.hmdp.ai.evaluation.VerificationStatus.NOT_EXECUTED
                : failures.isEmpty() ? com.hmdp.ai.evaluation.VerificationStatus.VERIFIED_PASS
                : com.hmdp.ai.evaluation.VerificationStatus.VERIFIED_FAIL;
        return new VerifiedExecution(new StaticPlanExecutor.ExecutionResult(execution.groups(), List.copyOf(observations)),
                List.copyOf(failures), status);
    }

    private DecisionTaskState applyPost(ConversationWorkingMemory memory, String taskId,
                                        StaticPlanExecutor.ExecutionResult execution) {
        DecisionTaskState task = findTask(memory, taskId);
        if (task == null) throw new IllegalStateException("V2 task disappeared before post commit");
        V2TaskState reduced = postReducer.apply(stateGateway.read(task), execution);
        stateGateway.write(task, reduced);
        return task;
    }

    private ChatMessageResponse response(String chatId, DecisionTaskState task,
                                        StaticPlanExecutor.ExecutionResult execution) {
        List<DecisionRecommendation> recommendations = new ArrayList<>();
        String fact = null, general = null, error = null;
        boolean unsupported = false;
        Long selectedId = null;
        for (ExecutionObservation observation : execution.observations()) {
            recommendations.addAll(observation.recommendations());
            if (observation.generalAnswer() != null) {
                if (observation.requestId().startsWith("general")) general = observation.generalAnswer();
                else fact = observation.generalAnswer();
            }
            if (observation.status() == ExecutionObservation.Status.FAILURE
                    || observation.status() == ExecutionObservation.Status.TIMEOUT
                    || observation.status() == ExecutionObservation.Status.CANCELLED
                    || observation.status() == ExecutionObservation.Status.UNSUPPORTED) {
                error = observation.detail() == null ? "本轮请求暂时无法完成。" : observation.detail();
                unsupported |= observation.status() == ExecutionObservation.Status.UNSUPPORTED;
            }
            for (ExecutionAction.DomainEffect effect : observation.effects())
                if (effect instanceof ExecutionAction.DomainEffect.CandidateSelected selected) selectedId = selected.shopId();
        }
        String selectedName = selectedId == null ? null : candidateName(task, selectedId);
        ResponseSpec spec = new ResponseSpec(recommendations, fact, selectedId, selectedName, general, null, error, unsupported);
        return renderer.render(chatId, spec);
    }

    private String candidateName(DecisionTaskState task, Long shopId) {
        for (RecommendationBatch batch : task.getRecommendationBatches())
            for (RecommendationCandidateRef candidate : batch.getCandidates())
                if (Objects.equals(candidate.getShopId(), shopId)) return candidate.getShopName();
        Shop shop = shopMapper.selectById(shopId);
        return shop == null ? null : shop.getName();
    }

    private PlanningSnapshot snapshot(DecisionTaskState task, ConversationWorkingMemory memory, int version) {
        V2TaskState state = stateGateway.read(task);
        return new PlanningSnapshot(version, task.getTaskId(), state.criteria(), state.relativePreferences(),
                state.rejectedShopIds(), state.relaxable(), state.locked(), state.searchAnchor(), currentVisibleIds(task));
    }

    private boolean sameCausalInputs(PlanningSnapshot left, PlanningSnapshot right) {
        return Objects.equals(left.taskId(), right.taskId()) && Objects.equals(left.criteria(), right.criteria())
                && Objects.equals(left.relativePreferences(), right.relativePreferences())
                && Objects.equals(left.rejectedShopIds(), right.rejectedShopIds())
                && Objects.equals(left.currentVisibleShopIds(), right.currentVisibleShopIds())
                && Objects.equals(left.searchAnchor(), right.searchAnchor())
                && Objects.equals(left.relaxable(), right.relaxable()) && Objects.equals(left.locked(), right.locked());
    }

    private Set<Long> currentVisibleIds(DecisionTaskState task) {
        if (task.getRecommendationBatches() == null || task.getRecommendationBatches().isEmpty()) return Set.of();
        return task.getRecommendationBatches().getLast().getCandidates().stream().map(RecommendationCandidateRef::getShopId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private List<TaskView> taskViews(ConversationWorkingMemory memory) {
        List<TaskView> result = new ArrayList<>();
        for (int i = 0; i < memory.getTasks().size(); i++) result.add(view(memory.getTasks().get(i), i));
        return result;
    }

    private TaskView view(DecisionTaskState task, int index) {
        RecommendationBatch batch = task.getRecommendationBatches().isEmpty() ? null : task.getRecommendationBatches().getLast();
        String batchId = batch == null ? null : batch.getBatchId();
        if (batch != null && (batchId == null || batchId.isBlank())) batchId = "batch-" + task.getRecommendationBatches().size();
        TaskView.RecommendationBatchView visible = batch == null ? null : new TaskView.RecommendationBatchView(batchId,
                batch.getCandidates().stream().filter(c -> c.getShopId() != null)
                        .map(c -> new TaskView.ShopView(c.getShopId(), c.getShopName())).toList());
        return new TaskView(task.getTaskId(), index, "DINING",
                task.getV2Criteria().location() == null ? null : task.getV2Criteria().location().city(), visible,
                task.getV2SelectedShopId());
    }

    private DecisionTaskState findTask(ConversationWorkingMemory memory, String taskId) {
        return memory.getTasks().stream().filter(task -> Objects.equals(task.getTaskId(), taskId)).findFirst().orElse(null);
    }

    private VersionedMemory load(String chatId) {
        AiWorkingMemory row = versions.latest(chatId);
        try {
            return row == null ? new VersionedMemory(0, new ConversationWorkingMemory())
                    : new VersionedMemory(row.getVersion(), objectMapper.readValue(row.getMemoryJson(), ConversationWorkingMemory.class));
        } catch (Exception e) {
            throw new IllegalStateException("V2 working memory cannot be read", e);
        }
    }

    private ChatMessageResponse clarification(String chatId, String message) {
        return renderer.render(chatId, new ResponseSpec(List.of(), null, null, null, null, message, null));
    }

    private ChatMessageResponse retryResponse(String chatId) {
        return renderer.render(chatId, new ResponseSpec(List.of(), null, null, null, null, null,
                "对话状态刚刚更新，本轮结果未展示，请重试这句话。", null, false, true));
    }

    private Map<String, Object> postEvent(PreparedTurn prepared, PlanRun run, String phase, boolean replanned) {
        List<Map<String, Object>> executed = run.execution().observations().stream().map(observation -> {
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("requestId", observation.requestId()); action.put("status", observation.status().name());
            return action;
        }).toList();
        List<Long> candidateIds = run.execution().observations().stream().flatMap(observation -> observation.candidateShopIds().stream()).distinct().toList();
        List<Map<String, Object>> groundedEntities = prepared.grounded().requests().stream().flatMap(request -> request.operands().stream())
                .filter(GroundedReference.ShopIdentity.class::isInstance).map(GroundedReference.ShopIdentity.class::cast)
                .map(shop -> Map.<String, Object>of("shopId", shop.shopId(), "batchId", shop.batchId() == null ? "" : shop.batchId(), "ordinal", shop.ordinal())).toList();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("phase", phase); event.put("taskId", prepared.taskId()); event.put("replanned", replanned);
        event.put("executedActions", executed); event.put("verifiedCandidateIds", candidateIds);
        event.put("verificationFailures", run.verificationFailures()); event.put("groundedEntities", groundedEntities);
        event.put("verificationStatus", run.verificationStatus().name());
        event.put("conditionalCriteriaApplied", run.execution().observations().stream().flatMap(o -> o.effects().stream())
                .anyMatch(ExecutionAction.DomainEffect.ConditionalCriteriaApplied.class::isInstance));
        return event;
    }

    /** Trace-only semantic projection for evaluation; execution still consumes the typed TurnSemantics object. */
    private Map<String, Object> preEvent(TurnSemantics semantics) {
        Map<String, Object> semantic = new LinkedHashMap<>();
        semantic.put("taskDirective", semantics.taskDirective().name());
        semantic.put("taskDirectiveEvidence", semantics.taskDirectiveEvidence().name());
        semantic.put("criteriaPatchCount", semantics.requirementChanges().stream()
                .filter(com.hmdp.ai.v2.semantic.RequirementChange.CriteriaPatch.class::isInstance).count());
        semantic.put("criteriaPatchDimensions", semantics.requirementChanges().stream()
                .filter(com.hmdp.ai.v2.semantic.RequirementChange.CriteriaPatch.class::isInstance)
                .map(com.hmdp.ai.v2.semantic.RequirementChange.CriteriaPatch.class::cast)
                .flatMap(change -> changedDimensions(change).stream()).distinct().toList());
        semantic.put("cleared", semantics.requirementChanges().stream()
                .filter(com.hmdp.ai.v2.semantic.RequirementChange.CriteriaPatch.class::isInstance)
                .map(com.hmdp.ai.v2.semantic.RequirementChange.CriteriaPatch.class::cast)
                .flatMap(change -> change.cleared().stream()).map(Enum::name).toList());
        semantic.put("relativePreferences", semantics.requirementChanges().stream()
                .filter(com.hmdp.ai.v2.semantic.RequirementChange.RelativePreference.class::isInstance)
                .map(com.hmdp.ai.v2.semantic.RequirementChange.RelativePreference.class::cast)
                .map(change -> Map.of("dimension", change.dimension().name(), "direction", change.direction().name())).toList());
        semantic.put("feedbackKinds", semantics.entityFeedback().stream()
                .map(item -> item instanceof com.hmdp.ai.v2.semantic.EntityFeedback.EntityFeedbackItem entity
                        ? entity.kind().name() : "BATCH_" + ((com.hmdp.ai.v2.semantic.EntityFeedback.BatchFeedback) item).polarity().name()).toList());
        semantic.put("feedback", semantics.entityFeedback().stream().map(item -> {
            if (item instanceof com.hmdp.ai.v2.semantic.EntityFeedback.EntityFeedbackItem entity)
                return Map.of("scope", "ENTITY", "kind", entity.kind().name(), "aspect", entity.aspect().name(), "reference", entity.target().getClass().getSimpleName());
            var batch = (com.hmdp.ai.v2.semantic.EntityFeedback.BatchFeedback) item;
            return Map.of("scope", "BATCH", "polarity", batch.polarity().name(), "aspect", batch.aspect().name());
        }).toList());
        semantic.put("requestKinds", semantics.requests().stream().map(request -> request.getClass().getSimpleName()).toList());
        semantic.put("referenceKinds", semanticReferences(semantics).stream().map(reference -> reference.getClass().getSimpleName()).toList());
        semantic.put("relationCount", semantics.relations().size());
        semantic.put("conditionalChangeCount", semantics.requirementChanges().stream()
                .filter(com.hmdp.ai.v2.semantic.RequirementChange.ConditionalRequirementChange.class::isInstance).count());
        return Map.of("phase", "V2_PRE", "semantic", semantic);
    }

    private List<String> changedDimensions(com.hmdp.ai.v2.semantic.RequirementChange.CriteriaPatch change) {
        var patch = change.patch(); List<String> dimensions = new ArrayList<>();
        if (patch.location() != null) dimensions.add("LOCATION"); if (patch.cuisine() != null) dimensions.add("CUISINE");
        if (patch.budget() != null) dimensions.add("BUDGET"); if (patch.distance() != null) dimensions.add("DISTANCE");
        if (patch.diningTime() != null) dimensions.add("DINING_TIME"); if (patch.semanticPreferences() != null) dimensions.add("SEMANTIC_PREFERENCES");
        return dimensions;
    }
    private boolean hasLocationMutation(TurnSemantics semantics) {
        return semantics.requirementChanges().stream().filter(com.hmdp.ai.v2.semantic.RequirementChange.CriteriaPatch.class::isInstance)
                .map(com.hmdp.ai.v2.semantic.RequirementChange.CriteriaPatch.class::cast)
                .anyMatch(change -> change.patch().location() != null || change.cleared().contains(com.hmdp.ai.v2.semantic.RequirementChange.ClearedCriterion.LOCATION));
    }

    private List<com.hmdp.ai.v2.semantic.EntityReference> semanticReferences(TurnSemantics semantics) {
        List<com.hmdp.ai.v2.semantic.EntityReference> result = new ArrayList<>(semantics.references());
        for (var feedback : semantics.entityFeedback()) if (feedback instanceof com.hmdp.ai.v2.semantic.EntityFeedback.EntityFeedbackItem entity) result.add(entity.target());
        for (var request : semantics.requests()) {
            if (request instanceof com.hmdp.ai.v2.semantic.UserRequest.FactQueryRequest fact) result.add(fact.target());
            if (request instanceof com.hmdp.ai.v2.semantic.UserRequest.SelectRequest select) result.add(select.target());
            if (request instanceof com.hmdp.ai.v2.semantic.UserRequest.SimilarRequest similar) result.add(similar.anchor());
            if (request instanceof com.hmdp.ai.v2.semantic.UserRequest.ExploreRequest explore && explore.target() != null) result.add(explore.target());
        }
        return result;
    }

    private String chatId(ChatMessageRequest request) { return request.getChatId(); }
    private Long userId() { return UserHolder.getUser() == null ? null : UserHolder.getUser().getId(); }

    private record VersionedMemory(int version, ConversationWorkingMemory memory) { }
    private record PreOutcome(PreparedTurn prepared, ChatMessageResponse response) { }
    private record PreBuild(String taskId, DecisionTaskState task, GroundedTurn grounded,
                            ChatMessageResponse response, boolean abandon, boolean persistBeforeResponse) {
        static PreBuild ready(String id, DecisionTaskState task, GroundedTurn grounded) { return new PreBuild(id, task, grounded, null, false, false); }
        static PreBuild response(ChatMessageResponse response) { return new PreBuild(null, null, null, response, false, false); }
        static PreBuild persistedResponse(String id, ChatMessageResponse response) { return new PreBuild(id, null, null, response, false, true); }
        static PreBuild abandon(String id) { return new PreBuild(id, null, null, null, true, false); }
    }
    private record PreparedTurn(ConversationWorkingMemory preMemory, int preVersion, String taskId, String activeTaskId,
                                TurnSemantics semantics, GroundedTurn grounded, PlanningSnapshot snapshot) { }
    private record VerifiedExecution(StaticPlanExecutor.ExecutionResult execution, List<String> failures,
                                     com.hmdp.ai.evaluation.VerificationStatus status) { }
    private record PlanRun(StaticPlanExecutor.ExecutionResult execution, Map<String, SearchSpec> searches,
                           List<String> verificationFailures,
                           com.hmdp.ai.evaluation.VerificationStatus verificationStatus) { }
}
