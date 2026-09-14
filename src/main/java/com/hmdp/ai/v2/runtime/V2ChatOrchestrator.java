package com.hmdp.ai.v2.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.ChatMessageRequest;
import com.hmdp.ai.dto.ChatMessageResponse;
import com.hmdp.ai.dto.ConversationWorkingMemory;
import com.hmdp.ai.dto.DecisionTaskState;
import com.hmdp.ai.dto.RecommendationBatch;
import com.hmdp.ai.dto.RecommendationCandidateRef;
import com.hmdp.ai.entity.AiWorkingMemory;
import com.hmdp.ai.runtime.ConversationEventType;
import com.hmdp.ai.service.ChatMemoryService;
import com.hmdp.ai.service.VersionConflictException;
import com.hmdp.ai.service.WorkingMemoryVersionService;
import com.hmdp.ai.v2.grounding.*;
import com.hmdp.ai.v2.plan.*;
import com.hmdp.ai.v2.reducer.PostExecutionReducer;
import com.hmdp.ai.v2.reducer.PreExecutionReducer;
import com.hmdp.ai.v2.reducer.V2TaskState;
import com.hmdp.ai.v2.semantic.*;
import com.hmdp.ai.v2.verification.DeterministicResultVerifier;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import java.util.*;

/** Production V2 turn boundary: both durable mutations use the existing append OCC contract. */
@Service
public class V2ChatOrchestrator {
    @Resource private SemanticInterpreter semanticInterpreter;
    @Resource private WorkingMemoryVersionService versions;
    @Resource private ChatMemoryService chatMemoryService;
    @Resource private ObjectMapper objectMapper;
    @Resource private StaticPlanExecutor executor;
    @Resource private V2ActionHandler actions;
    @Resource private V2ResponseRenderer renderer;
    @Resource private ShopMapper shopMapper;

    public ChatMessageResponse chat(ChatMessageRequest request) {
        if (request == null || request.getMessage() == null || request.getMessage().isBlank()) throw new IllegalArgumentException("message cannot be blank");
        String chatId = chatMemoryService.resolveChatId(request.getChatId()); request.setChatId(chatId);
        TurnSemantics semantics = semanticInterpreter.interpret(request.getMessage().trim());
        for (int attempt = 0; attempt < 2; attempt++) try {
            ChatMessageResponse response = run(chatId, request.getMessage().trim(), semantics);
            chatMemoryService.appendTurn(chatId, request.getMessage(), response.getAnswer(), response.getRoute(), null);
            return response;
        } catch (VersionConflictException conflict) {
            if (attempt == 1) return renderer.render(chatId, new ResponseSpec(List.of(), null, null, null, null,
                    "对话状态刚刚更新，请重试这句话。"));
        }
        throw new IllegalStateException("unreachable");
    }

    private ChatMessageResponse run(String chatId, String message, TurnSemantics semantics) {
        VersionedMemory loaded = load(chatId);
        ConversationWorkingMemory memory = loaded.memory();
        DecisionTaskState task = task(memory, semantics.taskDirective());
        List<TaskView> views = taskViews(memory);
        EffectiveTaskContextResult contextResult = new EffectiveTaskContextResolver().resolveResult(memory.getActiveTaskId(), views, semantics);
        if (contextResult instanceof EffectiveTaskContextResult.NeedsClarification c)
            return renderer.render(chatId, new ResponseSpec(List.of(), null, null, null, "请说明你指的是哪一个推荐任务：" + c.ambiguity().detail(), null));
        EffectiveTaskContext context = ((EffectiveTaskContextResult.Resolved) contextResult).context();
        if (context.task() != null) task = findTask(memory, context.task().taskId());
        GroundingResult grounding = new GroundingResolver().resolve(semantics,
                context.task() == null ? new EffectiveTaskContext(view(task, 0), false) : context);
        if (grounding instanceof GroundingResult.NeedsClarification c)
            return renderer.render(chatId, new ResponseSpec(List.of(), null, null, null, "请明确是哪一家：" + c.ambiguities().getFirst().detail(), null));
        GroundedTurn grounded = ((GroundingResult.Grounded) grounding).turn();
        V2TaskState previous = state(task);
        V2TaskState reduced = new PreExecutionReducer().reduce(previous, semantics);
        persistState(task, reduced);
        applyFeedback(task, grounded);
        AiWorkingMemory pre = versions.append(chatId, userId(), loaded.version(), memory, ConversationEventType.STATE_REDUCED,
                Map.of("phase", "V2_PRE"), Map.of("taskId", task.getTaskId()));
        PlanningSnapshot snapshot = snapshot(task, pre.getVersion());
        CompilationResult compiled = new ExecutionPlanCompiler().compile(grounded, snapshot);
        StaticPlanExecutor.ExecutionResult execution = execute(compiled);
        new DeterministicResultVerifier().verify(execution.observations().stream().filter(o -> o.status() == ExecutionObservation.Status.FAILURE)
                .map(ExecutionObservation::detail).toList(), List.of());
        applyEffects(task, execution);
        ResponseSpec response = response(task, execution);
        // A recommendation is visible only after this append wins. A conflict retries from the latest committed state.
        versions.append(chatId, userId(), pre.getVersion(), memory, ConversationEventType.STATE_REDUCED,
                Map.of("phase", "V2_POST"), Map.of("taskId", task.getTaskId()));
        return renderer.render(chatId, response);
    }

    private StaticPlanExecutor.ExecutionResult execute(CompilationResult compilation) {
        if (compilation instanceof CompilationResult.StaticPlan staticPlan) return executor.execute(staticPlan.plan(), actions);
        if (compilation instanceof CompilationResult.Direct direct) {
            ExecutionObservation observation = actions.execute(direct.action());
            return new StaticPlanExecutor.ExecutionResult(Map.of(), List.of(observation));
        }
        return new StaticPlanExecutor.ExecutionResult(Map.of(), List.of(new ExecutionObservation("adaptive", ExecutionObservation.Status.FAILURE,
                List.of(), null, List.of(), null, "V2 adaptive research is not available yet")));
    }
    private void applyEffects(DecisionTaskState task, StaticPlanExecutor.ExecutionResult execution) {
        V2TaskState state = state(task); PostExecutionReducer reducer = new PostExecutionReducer();
        for (ExecutionObservation observation : execution.observations()) for (ExecutionAction.DomainEffect effect : observation.effects()) {
            state = reducer.apply(state, effect);
            if (effect instanceof ExecutionAction.DomainEffect.CandidateSelected selected) task.setV2SelectedShopId(selected.shopId());
        }
        persistState(task, state);
        for (ExecutionObservation observation : execution.observations()) if (!observation.candidateShopIds().isEmpty()) appendBatch(task, observation.candidateShopIds());
    }
    private ResponseSpec response(DecisionTaskState task, StaticPlanExecutor.ExecutionResult result) {
        List<com.hmdp.ai.dto.DecisionRecommendation> recommendations = new ArrayList<>(); String fact = null; String general = null; String error = null;
        for (ExecutionObservation observation : result.observations()) {
            if (!observation.candidateShopIds().isEmpty()) for (Long id : observation.candidateShopIds()) { Shop shop = shopMapper.selectById(id); if (shop != null) {
                com.hmdp.ai.dto.DecisionRecommendation item = new com.hmdp.ai.dto.DecisionRecommendation(); item.setShopId(shop.getId()); item.setShopName(shop.getName()); item.setAvgPrice(shop.getAvgPrice()); item.setAddress(shop.getAddress()); recommendations.add(item); }}
            if (observation.generalAnswer() != null) { if (observation.requestId().startsWith("general")) general = observation.generalAnswer(); else fact = observation.generalAnswer(); }
            if (observation.status() == ExecutionObservation.Status.FAILURE) error = observation.detail();
        }
        return new ResponseSpec(recommendations, fact, task.getV2SelectedShopId(), general, null, error);
    }
    private void appendBatch(DecisionTaskState task, List<Long> ids) { RecommendationBatch batch = new RecommendationBatch();
        for (Long id : ids) { Shop shop = shopMapper.selectById(id); if (shop == null) continue; RecommendationCandidateRef candidate = new RecommendationCandidateRef(); candidate.setShopId(id); candidate.setShopName(shop.getName()); candidate.setPricePerPerson(shop.getAvgPrice()); batch.getCandidates().add(candidate); }
        if (!batch.getCandidates().isEmpty()) task.getRecommendationBatches().add(batch); }
    private void applyFeedback(DecisionTaskState task, GroundedTurn turn) { for (GroundedFeedback feedback : turn.feedback()) if (feedback.feedback() instanceof EntityFeedback.EntityFeedbackItem item && item.kind() == EntityFeedback.FeedbackKind.REJECT)
        feedback.operands().stream().filter(GroundedReference.ShopIdentity.class::isInstance).map(GroundedReference.ShopIdentity.class::cast).forEach(shop -> task.getV2RejectedShopIds().add(shop.shopId())); }
    private PlanningSnapshot snapshot(DecisionTaskState task, int version) { return new PlanningSnapshot(version, task.getTaskId(), task.getV2Criteria(), task.getV2RelativePreferences(), task.getV2RejectedShopIds(), task.getV2Relaxable(), task.getV2Locked(), task.getV2SearchAnchor()); }
    private V2TaskState state(DecisionTaskState task) { return new V2TaskState(task.getV2Criteria(), task.getV2RelativePreferences(), task.getV2Relaxable(), task.getV2Locked()); }
    private void persistState(DecisionTaskState task, V2TaskState state) { task.setV2Criteria(state.criteria()); task.setV2RelativePreferences(state.relativePreferences()); task.setV2Relaxable(state.relaxable()); task.setV2Locked(state.locked()); }
    private DecisionTaskState task(ConversationWorkingMemory memory, TaskDirective directive) { if (directive == TaskDirective.START_NEW || memory.activeTask() == null) return memory.ensureActiveTask(); return memory.activeTask(); }
    private DecisionTaskState findTask(ConversationWorkingMemory memory, String id) { return memory.getTasks().stream().filter(t -> id.equals(t.getTaskId())).findFirst().orElseThrow(); }
    private List<TaskView> taskViews(ConversationWorkingMemory memory) { List<TaskView> result = new ArrayList<>(); for (int i = 0; i < memory.getTasks().size(); i++) result.add(view(memory.getTasks().get(i), i)); return result; }
    private TaskView view(DecisionTaskState task, int index) { RecommendationBatch batch = task.getRecommendationBatches().isEmpty() ? null : task.getRecommendationBatches().getLast(); TaskView.RecommendationBatchView visible = batch == null ? null : new TaskView.RecommendationBatchView("batch-" + task.getRecommendationBatches().size(), batch.getCandidates().stream().filter(c -> c.getShopId() != null).map(c -> new TaskView.ShopView(c.getShopId(), c.getShopName())).toList()); return new TaskView(task.getTaskId(), index, "DINING", task.getV2Criteria().location() == null ? null : task.getV2Criteria().location().city(), visible, task.getV2SelectedShopId()); }
    private VersionedMemory load(String chatId) { AiWorkingMemory row = versions.latest(chatId); try { return row == null ? new VersionedMemory(0, new ConversationWorkingMemory()) : new VersionedMemory(row.getVersion(), objectMapper.readValue(row.getMemoryJson(), ConversationWorkingMemory.class)); } catch (Exception e) { throw new IllegalStateException("V2 working memory cannot be read", e); } }
    private Long userId() { return UserHolder.getUser() == null ? null : UserHolder.getUser().getId(); }
    private record VersionedMemory(int version, ConversationWorkingMemory memory) { }
}
