package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.client.SpringAiTextClient;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.AgentConversationRequest;
import com.hmdp.ai.dto.ChatMessageRequest;
import com.hmdp.ai.dto.ChatMessageResponse;
import com.hmdp.ai.dto.ChatStreamEventData;
import com.hmdp.ai.dto.DecisionFollowUpRequest;
import com.hmdp.ai.dto.DecisionRequest;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.DecisionContextFacts;
import com.hmdp.ai.dto.DecisionContextQuery;
import com.hmdp.ai.dto.ConversationLocationSlot;
import com.hmdp.ai.dto.ConversationSlots;
import com.hmdp.ai.dto.AgentSessionContext;
import com.hmdp.ai.dto.ContextRewriteResult;
import com.hmdp.ai.dto.CriteriaIntent;
import com.hmdp.ai.dto.TurnPlan;
import com.hmdp.ai.dto.TurnCommand;
import com.hmdp.ai.dto.TurnCommandSet;
import com.hmdp.ai.dto.ResolvedLocationCandidate;
import com.hmdp.ai.dto.LocationResolutionContext;
import com.hmdp.ai.dto.LocationResolutionRequest;
import com.hmdp.ai.dto.PolicyDecision;
import com.hmdp.ai.entity.AiChatSession;
import com.hmdp.ai.geo.AdministrativeResolution;
import com.hmdp.ai.geo.AdministrativeRegion;
import com.hmdp.ai.geo.AdministrativeRegionResolver;
import com.hmdp.ai.runtime.ConversationEventStatus;
import com.hmdp.ai.runtime.ConversationEventType;
import com.hmdp.ai.runtime.RoutingDecisionAssessment;
import com.hmdp.ai.service.pipeline.BootstrapNode;
import com.hmdp.ai.service.pipeline.ChatPipeline;
import com.hmdp.ai.service.pipeline.ChatPipelineNode;
import com.hmdp.ai.service.pipeline.ChatPipelineOperations;
import com.hmdp.ai.service.pipeline.ChatProcessingContext;
import com.hmdp.ai.service.pipeline.ContextRewriteNode;
import com.hmdp.ai.service.pipeline.CriteriaReductionNode;
import com.hmdp.ai.service.pipeline.ExecutionNode;
import com.hmdp.ai.service.pipeline.IntentRoutingNode;
import com.hmdp.ai.service.pipeline.PolicyGuardNode;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class ChatOrchestrationService implements ChatPipelineOperations {
    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrationService.class);
    private final RecommendationCountResolver recommendationCountResolver = new RecommendationCountResolver();
    @Resource private OpenAiCompatibleClient aiClient;
    @Resource private SpringAiTextClient springAiTextClient;
    @Resource private AiProperties aiProperties;
    @Resource private ConsumptionDecisionService decisionService;
    @Resource private AgentConversationService conversationService;
    @Resource private ChatMemoryService chatMemoryService;
    @Resource private ConversationStateService conversationStateService;
    @Resource private DecisionFailureExplanationFormatter failureExplanationFormatter = new DecisionFailureExplanationFormatter();
    @Resource private LocationResolutionProvider locationResolutionService;
    @Resource private ConversationContextRewriter contextRewriter;
    @Resource private ConstraintExtractor constraintExtractor;
    @Resource private ConversationCriteriaMerger criteriaMerger;
    @Resource private PolicyDecisionEngine policyDecisionEngine;
    @Resource private ConversationEventService conversationEventService;
    @Resource private ObjectMapper objectMapper;
    @Resource private AdministrativeRegionResolver administrativeRegionResolver;
    @Resource private DecisionContextQueryService decisionContextQueryService;
    @Resource private TurnUnderstandingService turnUnderstandingService;

    public ChatMessageResponse chat(ChatMessageRequest request) {
        return chat(request, null);
    }

    /** Fixes the conversation scope before an idempotency record hashes the request. */
    public String resolveChatId(ChatMessageRequest request) {
        if (request == null) throw new IllegalArgumentException("request cannot be null");
        String chatId = chatMemoryService.resolveChatId(request.getChatId());
        request.setChatId(chatId);
        return chatId;
    }

    /**
     * Keeps state transitions synchronous and auditable while allowing the final safe
     * general-chat response to be emitted directly from the model stream.
     */
    public ChatMessageResponse chat(ChatMessageRequest request, Consumer<String> textDeltaConsumer) {
        return chat(request, textDeltaConsumer, null);
    }

    public ChatMessageResponse chat(ChatMessageRequest request, Consumer<String> textDeltaConsumer,
                                    Consumer<ChatStreamEventData> eventConsumer) {
        ChatProcessingContext context = new ChatProcessingContext(request, textDeltaConsumer);
        context.setEventConsumer(eventConsumer);
        chatPipeline().process(context);
        if (context.getResponse() == null) {
            throw new IllegalStateException("chat pipeline completed without a response");
        }
        return context.getResponse();
    }

    /**
     * A request-scoped pipeline keeps intermediate values out of services and tools.
     * The nodes deliberately do not persist state; ConversationStateService remains
     * the only WorkingMemory writer.
     */
    private ChatPipeline chatPipeline() {
        List<ChatPipelineNode> nodes = Arrays.<ChatPipelineNode>asList(
                new BootstrapNode(this),
                new ContextRewriteNode(this),
                new IntentRoutingNode(this),
                new CriteriaReductionNode(this),
                new PolicyGuardNode(this),
                new ExecutionNode(this));
        return new ChatPipeline(nodes);
    }

    @Override
    public void bootstrap(ChatProcessingContext context) {
        ChatMessageRequest request = context.getRequest();
        if (request == null || request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            throw new IllegalArgumentException("message cannot be blank");
        }
        context.setOriginalMessage(request.getMessage().trim());
        context.setChatId(chatMemoryService.resolveChatId(request.getChatId()));
        context.setChatHistory(chatMemoryService.load(context.getChatId()));
        if (conversationEventService != null) {
            conversationEventService.begin(context.getChatId(), context.getChatHistory().size() / 2 + 1);
            Map<String, Object> input = new LinkedHashMap<String, Object>();
            input.put("content", context.getOriginalMessage());
            input.put("decisionSessionId", request.getDecisionSessionId());
            conversationEventService.persistDurableEvent(ConversationEventType.USER_INPUT, ConversationEventStatus.SUCCESS,
                    null, null, input, null);
        }
        AiChatSession state = conversationStateService.getOrCreate(context.getChatId());
        if (request.getLocation() != null) conversationStateService.acceptLocation(state, request.getLocation());
        context.setChatSession(state);
        context.setWorkingMemory(conversationStateService.workingMemory(state));
        Long activeSessionId = resolveActiveSessionId(state, request.getDecisionSessionId());
        context.setActiveDecisionSessionId(activeSessionId);
        context.setActiveDecision(activeSessionId == null ? null : decisionService.getDecision(activeSessionId));
        context.setRoutingAssessment(assessRouting(context, false));
        log.info("[AI][chat] event=MEMORY_LOADED chatId={} messages={}", context.getChatId(), context.getChatHistory().size());
        log.info("[AI][chat] event=TURN_START chatId={} clientSessionId={} activeSessionId={} status={} query={}",
                context.getChatId(), request.getDecisionSessionId(), activeSessionId,
                context.getActiveDecision() == null ? "NONE" : context.getActiveDecision().getStatus(),
                compact(context.getOriginalMessage()));
    }

    @Override
    public void rewrite(ChatProcessingContext context) {
        RoutingDecisionAssessment assessment = context.getRoutingAssessment();
        if (assessment != null && (!assessment.isContextRequired() || assessment.isConflictDetected()
                || assessment.isContextResolved())) {
            context.setContextRewrite(ContextRewriteResult.unchanged(context.getOriginalMessage(), "CONTEXT_RESOLUTION_NOT_REQUIRED"));
        } else {
            context.setContextRewrite(rewriteContext(context.getOriginalMessage(), context.getChatHistory(),
                    context.getChatSession(), context.getActiveDecisionSessionId()));
        }
        context.setEffectiveMessage(context.getContextRewrite().getRewrittenQuery());
        if (assessment != null && assessment.isContextRequired()) {
            assessment.setContextResolved(Boolean.TRUE.equals(context.getContextRewrite().getApplied())
                    || !context.getOriginalMessage().equals(context.getEffectiveMessage()));
            assessment.setRequiredContextMissing(!assessment.isContextResolved());
        }
        if (conversationEventService != null) {
            Map<String, Object> rewrite = new LinkedHashMap<String, Object>();
            rewrite.put("original", context.getOriginalMessage());
            rewrite.put("rewritten", context.getEffectiveMessage());
            rewrite.put("applied", context.getContextRewrite().getApplied());
            rewrite.put("reason", context.getContextRewrite().getReason());
            conversationEventService.recordBestEffort(ConversationEventType.REWRITE, ConversationEventStatus.SUCCESS,
                    null, null, rewrite, null);
        }
    }

    @Override
    public void route(ChatProcessingContext context) {
        RoutingDecisionAssessment assessment = assessRouting(context, true);
        context.setRoutingAssessment(assessment);
        ChatMessageRequest request = context.getRequest();
        DecisionResponse activeDecision = context.getActiveDecision();
        String message = context.getOriginalMessage();
        TurnCommandSet turnCommands = turnUnderstandingService == null
                ? new TurnCommandSet()
                : turnUnderstandingService.understand(message, context.getEffectiveMessage(),
                context.getChatHistory(), context.getContextRewrite(),
                activeDecision == null ? null : activeDecision.getStatus(),
                activeDecision == null ? activeCriteriaForTurn(context) : activeDecision.getConstraints());
        context.setTurnCommandSet(turnCommands);
        if (request.getSelectedOptionId() == null
                && isWaitingRelaxation(activeDecision)
                && turnCommands.hasCommand(TurnCommand.Type.BROADEN_FOOD_SCOPE)) {
            request.setSelectedOptionId(com.hmdp.ai.runtime.DecisionCommand.BROADEN_FOOD_SCOPE.name());
            selectAction(context, com.hmdp.ai.service.pipeline.ChatProcessingAction.DECISION_EVENT,
                    "waiting_relaxation_broaden_food_scope");
            assessment.setCandidateAction(com.hmdp.ai.service.pipeline.ChatProcessingAction.DECISION_EVENT);
            assessment.setSource("RULE");
            return;
        }
        if (request.getSelectedOptionId() != null && isPausedDecision(activeDecision)) {
            selectAction(context, com.hmdp.ai.service.pipeline.ChatProcessingAction.DECISION_EVENT, "selected_option_for_paused_decision");
            return;
        }
        if (isDecisionContextQuery(message) || turnCommands.isContextQueryRequested()) {
            selectAction(context, com.hmdp.ai.service.pipeline.ChatProcessingAction.DECISION_CONTEXT_QUERY,
                    "decision_context_query");
            assessment.setCandidateAction(com.hmdp.ai.service.pipeline.ChatProcessingAction.DECISION_CONTEXT_QUERY);
            assessment.setSource("RULE");
            boolean reference = isReferenceMessage(message) || hasResolvedReference(context);
            assessment.setContextRequired(reference);
            assessment.setContextResolved(!reference || (context.getContextRewrite() != null
                    && context.getContextRewrite().getResolvedReferences() != null
                    && !context.getContextRewrite().getResolvedReferences().isEmpty()));
            assessment.setRequiredContextMissing(reference && !assessment.isContextResolved());
            return;
        }
        if (isLocationClarification(activeDecision) && request.getLocation() == null) {
            int pendingIndex = uniquePendingCandidateIndex(message, context.getChatSession());
            if (pendingIndex >= 0) {
                request.setSelectedOptionId("CONFIRM_RESOLVED_LOCATION_" + pendingIndex);
                selectAction(context, com.hmdp.ai.service.pipeline.ChatProcessingAction.DECISION_EVENT,
                        "natural_language_location_confirmation");
                assessment.setSource("RULE");
                return;
            }
            String poiOverride = extractClarificationPoiQuery(message);
            if (hasText(poiOverride)) {
                selectAction(context, com.hmdp.ai.service.pipeline.ChatProcessingAction.LOCATION_RESOLUTION,
                        "named_location_override_for_clarification");
                assessment.setSource("RULE");
                ChatMessageResponse locationResponse = buildLocationResolutionResponse(context.getChatId(), message,
                        context.getChatSession(), context.getActiveDecisionSessionId(), activeDecision, poiOverride,
                        context.getCriteriaDelta() == null ? null : context.getCriteriaDelta().getEntityTypeHint());
                if (locationResponse != null) {
                    context.setResponse(locationResponse);
                    return;
                }
            }
        }
        if (isLocationClarification(activeDecision) && request.getLocation() == null && isPotentialNamedLocation(message)) {
            selectAction(context, com.hmdp.ai.service.pipeline.ChatProcessingAction.LOCATION_RESOLUTION, "named_location_for_clarification");
            assessment.setSource("RULE");
            ChatMessageResponse locationResponse = resolveNamedLocation(context.getChatId(), message,
                    context.getChatSession(), context.getActiveDecisionSessionId(), activeDecision);
            if (locationResponse != null) {
                context.setResponse(locationResponse);
                return;
            }
        }
        if (isSuspendedDecision(activeDecision) && context.getContextRewrite() != null
                && "REFERENCE_UNRESOLVED".equals(context.getContextRewrite().getReason())) {
            selectAction(context, com.hmdp.ai.service.pipeline.ChatProcessingAction.EXPLAIN_SUSPENDED, "unresolved_reference_in_suspended_decision");
            assessment.setSource("CONTEXT");
            return;
        }
        if (isSuspendedDecision(activeDecision) && request.getSelectedOptionId() == null
                && isSuspendedDecisionMetaQuestion(message)) {
            selectAction(context, com.hmdp.ai.service.pipeline.ChatProcessingAction.EXPLAIN_SUSPENDED, "suspended_decision_meta_question");
            assessment.setSource("RULE");
            return;
        }
        // 位置恢复（B 修复 #case30）：WAITING_RELAXATION 态下纯位置表达 → 保留约束换位置重搜，
        // 而非 START_DECISION 重开。TODO(C重构): 迁入 RoutingTransitionService 转移表
        if ("WAITING_RELAXATION".equals(activeDecision == null ? null : activeDecision.getStatus())
                && request.getSelectedOptionId() == null
                && isLocationRecoveryExpression(context.getOriginalMessage())) {
            selectAction(context, com.hmdp.ai.service.pipeline.ChatProcessingAction.DECISION_EVENT, "location_recovery_in_waiting_relaxation");
            assessment.setSource("RULE");
            return;
        }
        // 非餐饮领域守卫（#29 修复）：无餐饮强信号且命中非餐饮领域词表 → 强制 GENERAL_CHAT，
        // 必须先于暂停决策替换，避免旅游/运动等 OOS 话题继承旧餐饮 Criteria。
        if (isNonDiningDomain(context.getOriginalMessage())) {
            selectAction(context, com.hmdp.ai.service.pipeline.ChatProcessingAction.GENERAL_CHAT, "non_dining_domain_guard");
            // 领域守卫是确定性 RULE 拦截，修正 assessRouting 预评估落 MODEL 的统计口径（case3 审计 2026-09-03）
            assessment.setSource("RULE");
            assessment.setCandidateAction(com.hmdp.ai.service.pipeline.ChatProcessingAction.GENERAL_CHAT);
            log.info("[AI][chat] event=DOMAIN_GUARD_BLOCKED chatId={} message={}", context.getChatId(), compact(context.getOriginalMessage()));
            return;
        }
        boolean replacesPausedDecision = !assessment.isConflictDetected()
                && isPausedDecision(activeDecision) && request.getSelectedOptionId() == null
                && (isSearchRefinement(context.getOriginalMessage(), context.getEffectiveMessage())
                || isNewRecommendationIntent(context.getEffectiveMessage())
                || isContinuationRefinement(context.getEffectiveMessage(), context.getChatSession()));
        if (replacesPausedDecision) {
            cancelPausedDecision(context);
            selectAction(context, com.hmdp.ai.service.pipeline.ChatProcessingAction.START_DECISION, "new_recommendation_replaces_paused_decision");
            context.setUsedModel(false);
            assessment.setSource("RULE");
            return;
        }
        if (isAdministrativeLocationTurn(context)) {
            selectAction(context, com.hmdp.ai.service.pipeline.ChatProcessingAction.START_DECISION,
                    "administrative_location_scope");
            assessment.setSource("RULE");
            return;
        }
        if (!assessment.isConflictDetected()
                && assessment.getCandidateAction() == com.hmdp.ai.service.pipeline.ChatProcessingAction.BUSINESS_FOLLOW_UP
                && isCompoundMutationFollowUp(context)) {
            context.setRoute("BUSINESS_FOLLOW_UP");
            selectAction(context, com.hmdp.ai.service.pipeline.ChatProcessingAction.BUSINESS_FOLLOW_UP,
                    "structured_mutation_with_follow_up");
            return;
        }
        if (!assessment.isConflictDetected()
                && (isNewRecommendationIntent(message)
                || isSearchRefinement(context.getOriginalMessage(), context.getEffectiveMessage()))) {
            selectAction(context, com.hmdp.ai.service.pipeline.ChatProcessingAction.START_DECISION, "new_recommendation_intent");
            context.setUsedModel(aiProperties.isConfigured());
            return;
        }
        String route = assessment.isConflictDetected() ? null
                : resolveContextualFollowUpRoute(context.getChatId(), context.getEffectiveMessage(), context.getChatSession(),
                context.getActiveDecisionSessionId(), activeDecision, context.getContextRewrite());
        if (route == null) {
            String modelRoute = route(context.getEffectiveMessage(), activeDecision == null ? "NONE" : activeDecision.getStatus(), context.getChatHistory());
            route = validateModelRoute(modelRoute, activeDecision, context.getEffectiveMessage());
            assessment.setSource("MODEL");
            assessment.setCandidateAction(toProcessingAction(route));
            assessment.setStateAllowed(isRouteAllowed(route, activeDecision));
            assessment.setShouldEscalate(false);
            assessment.setReason("routing_model_candidate_validated");
        } else if ("MODEL".equals(assessment.getSource())) {
            // resolveContextualFollowUpRoute 是确定性 follow-up 路由（ShopInquiry/候选引用），非 LLM 兜底，
            // 回写 source 防止统计层误标（一致性断言：确定性推导的 action 不得 source=MODEL）
            assessment.setSource("RULE");
        }
        context.setRoute(route);
        selectAction(context, toProcessingAction(route), "resolved_route:" + route);
        context.setUsedModel(aiProperties.isConfigured());
        if (conversationEventService != null) {
            conversationEventService.recordBestEffort(ConversationEventType.ROUTE_DECISION, ConversationEventStatus.SUCCESS,
                    null, null, java.util.Collections.<String, Object>singletonMap("route", route), null);
        }
        log.info("[AI][chat] event=ROUTE_SELECTED chatId={} activeSessionId={} route={}",
                context.getChatId(), context.getActiveDecisionSessionId(), route);
    }

    @Override
    public void reduceCriteria(ChatProcessingContext context) {
        if (context.getTurnPlan() == null || context.getTurnPlan().getCriteriaIntent() != CriteriaIntent.APPLY_DELTA) return;
        prepareDecision(context);
    }

    @Override
    public void applyPolicyGuard(ChatProcessingContext context) {
        if (context.getAction() != com.hmdp.ai.service.pipeline.ChatProcessingAction.START_DECISION
                || context.getWorkingMemory() == null || context.getMergedConstraints() == null) {
            return;
        }
        context.setPolicyDecision(policyDecisionEngine == null ? null : policyDecisionEngine.decideRecommendation(
                context.getDecisionRequest(), context.getMergedConstraints(), context.getWorkingMemory()));
        if (context.getPolicyDecision() != null) {
            recordPolicy(context.getChatSession(), context.getChatId(), null, context.getPolicyDecision());
        }
    }

    @Override
    public void execute(ChatProcessingContext context) {
        context.setResponse(executeAction(context));
    }

    private void cancelPausedDecision(ChatProcessingContext context) {
        DecisionFollowUpRequest cancel = new DecisionFollowUpRequest();
        cancel.setSelectedOptionId("END_DECISION");
        cancel.setMessage("被新的餐饮需求替代：" + context.getEffectiveMessage());
        decisionService.continueDecision(context.getActiveDecisionSessionId(), cancel);
        conversationStateService.clearActiveDecision(context.getChatSession());
        log.info("[AI][chat] event=PENDING_DECISION_SUPERSEDED chatId={} previousSessionId={} query={}",
                context.getChatId(), context.getActiveDecisionSessionId(), compact(context.getEffectiveMessage()));
    }

    private void prepareDecision(ChatProcessingContext context) {
        DecisionRequest request = new DecisionRequest();
        request.setMaxCandidates(recommendationCountResolver.resolve(context.getOriginalMessage()));
        context.setDecisionRequest(request);
        if (context.getWorkingMemory() == null) {
            request.setQuery(context.getEffectiveMessage());
            return;
        }
        com.hmdp.ai.dto.DecisionConstraints activeCriteriaBeforeExtraction = conversationStateService.activeCriteria(context.getWorkingMemory());
        com.hmdp.ai.dto.DecisionConstraints extracted = context.getCriteriaDelta();
        if (extracted == null) {
            // The original turn is the semantic source. Rewritten text may only enrich
            // references and must never erase a second command such as an exclusion.
            extracted = constraintExtractor.extract(context.getOriginalMessage(), activeCriteriaBeforeExtraction);
            // Keeps older test doubles and optional integrations compatible while the production
            // extractor uses the context-aware overload for administrative disambiguation.
            if (extracted == null) extracted = constraintExtractor.extract(context.getEffectiveMessage());
        }
        context.setCriteriaDelta(extracted);
        enrichExplicitPoiAdministrativePrefix(context.getOriginalMessage(), extracted);
        com.hmdp.ai.dto.DecisionTaskState activeBefore = conversationStateService.activeTask(context.getWorkingMemory());
        com.hmdp.ai.service.ConversationStateService.TaskTransition transition = conversationStateService.transitionTask(
                context.getWorkingMemory(), extracted, context.getOriginalMessage());
        if ("EXPLICIT_HISTORY".equals(transition.reason())) {
            // Historical task selection supplies the missing slots from the task snapshot.
            // Model defaults such as clearedFields must not erase those slots on a return turn.
            extracted.setClearedFields(new ArrayList<String>());
            extracted.setRemovedPreferences(new ArrayList<String>());
        }
        com.hmdp.ai.dto.DecisionConstraints previous = conversationStateService.activeCriteria(context.getWorkingMemory());
        if (previous == null) previous = new com.hmdp.ai.dto.DecisionConstraints();
        log.info("[AI][task] event=TRANSITION chatId={} original={} effective={} action={} reason={} activeTaskBefore={} activeTaskAfter={} taskCount={} previous={} extracted={}",
                context.getChatId(), compact(context.getOriginalMessage()), compact(context.getEffectiveMessage()), transition.action(),
                transition.reason(), activeBefore == null ? null : activeBefore.getTaskId(), transition.activeTaskIdAfter(),
                context.getWorkingMemory().getTasks().size(), compact(previous.toString()), compact(extracted.toString()));
        // critique is a single source of truth: direction != 0 (LLM) OR refinement words (rule fast-path) both feed exclusion
        List<Long> excludedCandidates = refinementExclusions(context.getContextRewrite(), context.getWorkingMemory(), extracted);
        request.setExcludeShopIds(excludedCandidates);
        com.hmdp.ai.dto.CriteriaMergeResult mergeResult = criteriaMerger.merge(
                previous, extracted,
                context.getOriginalMessage(), conversationStateService.latestCandidatePool(context.getWorkingMemory()),
                context.getWorkingMemory().getFocusedShopId(), conversationStateService.shownShopIds(context.getWorkingMemory()),
                mutationAnchorShopId(context));
        context.setCriteriaMergeResult(mergeResult);
        context.setMergedConstraints(mergeResult.getConstraints());
        request.setQuery(cleanRetrievalQuery(context.getOriginalMessage(), mergeResult.getConstraints()));
        conversationStateService.reduceCriteria(context.getChatSession(), context.getWorkingMemory(), mergeResult);
        conversationStateService.applyNamedSearchLocation(context.getChatSession(), mergeResult.getConstraints());
        applyLocationSlot(request, context.getChatSession(), mergeResult.getConstraints());
        log.info("[AI][chat] event=CRITERIA_MERGED chatId={} inherited={} replaced={} appended={} cleared={} invalidated={} query={}",
                context.getChatId(), mergeResult.getInherited(), mergeResult.getReplaced(), mergeResult.getAppended(),
                mergeResult.getCleared(), mergeResult.getInvalidated(), compact(context.getEffectiveMessage()));
    }

    /** Selects the single execution action while preserving an independent mutation intent. */
    private void selectAction(ChatProcessingContext context,
                              com.hmdp.ai.service.pipeline.ChatProcessingAction action,
                              String reason) {
        context.setAction(action);
        context.setRoutingReason(reason);
        CriteriaIntent criteriaIntent = action == com.hmdp.ai.service.pipeline.ChatProcessingAction.START_DECISION
                ? CriteriaIntent.APPLY_DELTA : CriteriaIntent.NONE;
        if (action == com.hmdp.ai.service.pipeline.ChatProcessingAction.BUSINESS_FOLLOW_UP
                && hasResolvedReference(context)) {
            try {
                // Turn semantics is the sole authority for whether a reference follow-up may
                // mutate state. Only after that permission is granted do we extract the delta.
                if (hasStructuredReferenceMutation(context)) {
                    ensureCriteriaDelta(context);
                    criteriaIntent = CriteriaIntent.APPLY_DELTA;
                }
            } catch (RuntimeException ignored) {
                // Follow-up routing remains valid when optional extraction is unavailable.
            }
        }
        TurnPlan turnPlan = new TurnPlan(criteriaIntent, action);
        context.setTurnPlan(turnPlan);
        if (context.getRoutingAssessment() != null) context.getRoutingAssessment().setTurnPlan(turnPlan);
    }

    private boolean hasResolvedReference(ChatProcessingContext context) {
        return context.getContextRewrite() != null
                && context.getContextRewrite().getResolvedReferences() != null
                && !context.getContextRewrite().getResolvedReferences().isEmpty();
    }

    /** Administrative names are closed-world scope changes, not merchant follow-up questions. */
    private boolean isAdministrativeLocationTurn(ChatProcessingContext context) {
        if (administrativeRegionResolver == null || context.getOriginalMessage() == null) return false;
        if (!hasDiningIntentEvidence(context.getOriginalMessage())) return false;
        com.hmdp.ai.dto.DecisionConstraints active = context.getWorkingMemory() == null
                ? null : conversationStateService.activeCriteria(context.getWorkingMemory());
        AdministrativeResolution resolution = administrativeRegionResolver.resolve(context.getOriginalMessage(), active);
        return resolution != null && resolution.status() != AdministrativeResolution.Status.NOT_FOUND;
    }

    private boolean isCompoundMutationFollowUp(ChatProcessingContext context) {
        return hasStructuredReferenceMutation(context);
    }

    /**
     * The single permission gate for mutation on a resolved-reference follow-up.
     * Extracted constraint values are data only; they cannot grant mutation permission.
     * A missing semantic interpreter fails closed instead of reviving the old extractor
     * based authority.
     */
    private boolean hasStructuredReferenceMutation(ChatProcessingContext context) {
        if (!hasResolvedReference(context) || turnUnderstandingService == null) return false;
        TurnCommandSet commands = context.getTurnCommandSet();
        return commands != null
                && commands.isMutationRequested()
                && turnUnderstandingService.shouldApplyReferenceMutation(commands);
    }

    /**
     * A city written before a short POI is execution context, not a second POI.
     * Resolve that bounded prefix through the administrative authority before the
     * policy gate sees the extractor's conservative missing-region hint. The
     * resolver's raw-query grounding also rejects prefixes embedded in names such
     * as "福州大学".
     */
    private void enrichExplicitPoiAdministrativePrefix(String rawMessage,
                                                        com.hmdp.ai.dto.DecisionConstraints extracted) {
        if (administrativeRegionResolver == null || extracted == null
                || !hasText(extracted.getTargetArea()) || !hasText(rawMessage)
                || hasText(extracted.getTargetCity()) || hasText(extracted.getTargetProvince())) return;
        String query = normalizeLocationQuery(rawMessage).replaceAll("\\s+", "");
        String poi = extracted.getTargetArea().replaceAll("\\s+", "");
        if (query.isEmpty() || poi.isEmpty() || !query.endsWith(poi) || query.length() <= poi.length()) return;
        String prefix = query.substring(0, query.length() - poi.length());
        AdministrativeResolution prefixResolution = administrativeRegionResolver.resolveGeographicContextPrefix(prefix);
        if (prefixResolution.status() != AdministrativeResolution.Status.RESOLVED
                || prefixResolution.candidates().size() != 1) return;
        AdministrativeRegion candidate = prefixResolution.candidates().get(0);
        DecisionConstraints hint = new DecisionConstraints();
        hint.setTargetCity(candidate.getName());
        AdministrativeResolution grounded = administrativeRegionResolver.resolveHint(rawMessage, hint, null);
        if (grounded.status() != AdministrativeResolution.Status.RESOLVED) return;
        extracted.setTargetCity(candidate.getName());
        if (!hasText(extracted.getTargetProvince()) && hasText(candidate.getProvince())) {
            extracted.setTargetProvince(candidate.getProvince());
        }
        if (extracted.getMissingInformation() != null) {
            extracted.getMissingInformation().remove("administrativeRegion");
        }
        if (extracted.getClearedFields() != null) {
            extracted.getClearedFields().remove("targetCity");
            extracted.getClearedFields().remove("targetProvince");
        }
    }

    private void ensureCriteriaDelta(ChatProcessingContext context) {
        if (context.getCriteriaDelta() != null || constraintExtractor == null) return;
        com.hmdp.ai.dto.DecisionConstraints activeCriteria = context.getWorkingMemory() == null
                ? null : conversationStateService.activeCriteria(context.getWorkingMemory());
        context.setCriteriaDelta(constraintExtractor.extract(context.getOriginalMessage(), activeCriteria));
        if (context.getCriteriaDelta() == null) {
            // Compatibility fallback for test doubles and optional integrations that only implement the legacy API.
            context.setCriteriaDelta(constraintExtractor.extract(context.getOriginalMessage()));
        }
    }

    /** Narrow deterministic guard for read-only context questions; open phrasing remains model-routed. */
    private boolean isDecisionContextQuery(String message) {
        String text = message == null ? "" : message.replaceAll("\\s+", "");
        return isWhyRecommendedQuery(text) || isCurrentCriteriaQuery(text) || isExecutedSearchScopeQuery(text)
                || ((text.contains("说过") || text.contains("前面") || text.contains("之前"))
                && constraintKey(text) != null);
    }

    private boolean isExecutedSearchScopeQuery(String message) {
        String text = message == null ? "" : message.replaceAll("\\s+", "");
        return (text.contains("查哪里") || text.contains("查的是哪里") || text.contains("按哪里查")
                || text.contains("按哪个地区") || text.contains("搜的是哪里") || text.contains("搜的是哪个城市")
                || text.contains("搜索范围"))
                && (text.contains("刚刚") || text.contains("刚才") || text.contains("之前") || text.contains("前面"));
    }

    private boolean isReferenceMessage(String message) {
        if (message == null) return false;
        return isFocusedShopQuestion(message) || message.contains("第一家") || message.contains("第二家")
                || message.contains("第三家") || message.contains("首选");
    }

    private boolean isWhyRecommendedQuery(String message) {
        String text = message == null ? "" : message.replaceAll("\\s+", "");
        return (text.contains("推荐") && (text.contains("为什么") || text.contains("依据") || text.contains("理由")))
                || (text.contains("为什么") && text.contains("排"))
                || text.contains("根据什么推荐") || text.contains("什么理由推荐");
    }

    private boolean isCurrentCriteriaQuery(String message) {
        String text = message == null ? "" : message.replaceAll("\\s+", "");
        return (text.contains("当前") || text.contains("现在"))
                && (text.contains("条件") || text.contains("要求") || text.contains("依据"));
    }

    private String constraintKey(String message) {
        String text = message == null ? "" : message.replaceAll("\\s+", "");
        if (text.contains("预算")) return "budgetPerPerson";
        if (text.contains("附近")) return "nearby";
        if (text.contains("半径") || text.contains("距离") || text.contains("多远") || text.contains("更近")) return "radiusKm";
        if (text.contains("安静") || text.contains("聊天")) return "preference:安静";
        if (text.contains("约会")) return "preference:约会";
        if (text.contains("排队")) return "preference:不排队";
        if (text.contains("菜系") || text.contains("口味")) return "cuisine";
        if (text.contains("店名") || text.contains("关键词")) return "keyword";
        if (text.contains("省份")) return "targetProvince";
        if (text.contains("城市")) return "targetCity";
        if (text.contains("区县") || text.contains("地区")) return "targetDistrict";
        return null;
    }

    private Long mutationAnchorShopId(ChatProcessingContext context) {
        if (context.getContextRewrite() == null || context.getContextRewrite().getResolvedReferences() == null) return null;
        for (com.hmdp.ai.dto.ResolvedShopReference reference : context.getContextRewrite().getResolvedReferences()) {
            if (reference.intent() != null && reference.intent().isMutationAnchor()) return reference.shopId();
        }
        return null;
    }

    private ChatMessageResponse executeAction(ChatProcessingContext context) {
        switch (context.getAction()) {
            case DECISION_EVENT:
                ChatMessageResponse eventResponse = new ChatMessageResponse();
                eventResponse.setChatId(context.getChatId());
                eventResponse.setRoute("DECISION_EVENT");
                eventResponse.setUsedModel(false);
                return handleDecisionEvent(context.getChatId(), context.getOriginalMessage(), context.getRequest(),
                        context.getChatSession(), context.getActiveDecisionSessionId(), eventResponse);
            case LOCATION_RESOLUTION:
                ChatMessageResponse locationResponse = resolveNamedLocation(context.getChatId(), context.getOriginalMessage(),
                        context.getChatSession(), context.getActiveDecisionSessionId(), context.getActiveDecision());
                if (locationResponse != null) return locationResponse;
                return executeGeneralChat(context, "GENERAL_CHAT");
            case EXPLAIN_SUSPENDED:
                return explainSuspendedDecision(context.getChatId(), context.getOriginalMessage(),
                        context.getChatSession(), context.getActiveDecisionSessionId(), context.getActiveDecision());
            case START_DECISION:
                return executeDecision(context);
            case DECISION_CONTEXT_QUERY:
                return executeDecisionContextQuery(context);
            case BUSINESS_FOLLOW_UP:
                return executeBusinessFollowUp(context);
            case EXIT_DECISION:
                return executeExitDecision(context);
            case GENERAL_CHAT:
            case NONE:
            default:
                return executeGeneralChat(context, context.getRoute() == null ? "GENERAL_CHAT" : context.getRoute());
        }
    }

    private ChatMessageResponse executeDecision(ChatProcessingContext context) {
        if (context.getPolicyDecision() != null
                && PolicyDecisionEngine.RESOLVE_EXPLICIT_LOCATION.equals(context.getPolicyDecision().getAction())) {
            DecisionResponse pending = decisionService.decide(context.getDecisionRequest(), context.getMergedConstraints(),
                    context.getChatId(), conversationEventService == null || conversationEventService.currentTrace() == null
                            ? null : conversationEventService.currentTrace().getTraceId());
            conversationStateService.activateDecision(context.getChatSession(), pending.getSessionId());
            conversationStateService.snapshotDecision(context.getChatSession(), pending);
            // Keep the raw turn here so a city prefix (for example “北京农大”)
            // remains available to administrative-context enrichment. The
            // extracted targetArea is still used as the POI fallback after the
            // prefix has been resolved; passing it alone would silently drop
            // the explicit city and let device GPS win.
            String place = context.getOriginalMessage();
            ChatMessageResponse locationResponse = buildLocationResolutionResponse(context.getChatId(),
                    context.getOriginalMessage(), context.getChatSession(), pending.getSessionId(), pending, place,
                    context.getCriteriaDelta() == null ? null : context.getCriteriaDelta().getEntityTypeHint());
            if (locationResponse != null) return locationResponse;
            return buildDecisionResponse(context.getChatId(), context.getOriginalMessage(), context.getChatSession(),
                    context.isUsedModel(), context.getContextRewrite(), pending, context.getPolicyDecision());
        }
        if (context.getPolicyDecision() != null
                && PolicyDecisionEngine.CLARIFY_ADMINISTRATIVE_REGION.equals(context.getPolicyDecision().getAction())) {
            DecisionResponse clarification = new DecisionResponse();
            clarification.setStatus("CLARIFYING");
            clarification.setConstraints(context.getMergedConstraints());
            clarification.setQuestion("你说的地点范围还缺少所属城市或省份，请告诉我完整地点，例如“城市+区县”。");
            return buildDecisionResponse(context.getChatId(), context.getOriginalMessage(), context.getChatSession(),
                    context.isUsedModel(), context.getContextRewrite(), clarification, context.getPolicyDecision());
        }
        DecisionResponse decision;
        if (context.getWorkingMemory() == null) {
            decision = decisionService.decide(context.getDecisionRequest());
        } else {
            decision = decisionService.decide(context.getDecisionRequest(), context.getMergedConstraints(), context.getChatId(),
                    conversationEventService == null || conversationEventService.currentTrace() == null ? null
                            : conversationEventService.currentTrace().getTraceId());
        }
        if (conversationEventService != null) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("decisionSessionId", decision.getSessionId()); result.put("status", decision.getStatus());
            conversationEventService.recordBestEffort(ConversationEventType.DECISION_STARTED, ConversationEventStatus.SUCCESS,
                    null, null, result, null);
        }
        conversationStateService.activateDecision(context.getChatSession(), decision.getSessionId());
        conversationStateService.snapshotDecision(context.getChatSession(), decision);
        return buildDecisionResponse(context.getChatId(), context.getOriginalMessage(), context.getChatSession(), context.isUsedModel(),
                context.getContextRewrite(), decision, context.getPolicyDecision());
    }

    private ChatMessageResponse executeDecisionContextQuery(ChatProcessingContext context) {
        DecisionContextQuery query = buildDecisionContextQuery(context);
        context.setDecisionContextQuery(query);
        DecisionContextQueryService.QueryResult result = decisionContextQueryService == null
                ? new DecisionContextQueryService.QueryResult("目前无法读取决策上下文。", new DecisionContextFacts())
                : decisionContextQueryService.execute(context.getChatSession(), query);
        ChatMessageResponse response = newResponse(context, "DECISION_CONTEXT_QUERY");
        response.setDecisionContextQuery(query);
        response.setDecisionContextFacts(result.facts());
        response.setAnswer(result.answer());
        if (context.getActiveDecision() != null) {
            response.setDecisionSessionId(context.getActiveDecisionSessionId());
            response.setDecisionStatus(context.getActiveDecision().getStatus());
        }
        recordTurn(context.getChatId(), context.getOriginalMessage(), response);
        return response;
    }

    private DecisionContextQuery buildDecisionContextQuery(ChatProcessingContext context) {
        if (context.getTurnCommandSet() != null && context.getTurnCommandSet().getContextQuery() != null) {
            DecisionContextQuery structured = context.getTurnCommandSet().getContextQuery();
            if (structured.getType() == DecisionContextQuery.QueryType.WHY_RECOMMENDED
                    && context.getContextRewrite() != null
                    && context.getContextRewrite().getResolvedReferences() != null
                    && !context.getContextRewrite().getResolvedReferences().isEmpty()) {
                structured.setResolvedReference(context.getContextRewrite().getResolvedReferences().get(0));
            }
            return structured;
        }
        String message = context.getOriginalMessage() == null ? "" : context.getOriginalMessage().replaceAll("\\s+", "");
        DecisionContextQuery query = new DecisionContextQuery();
        if (isExecutedSearchScopeQuery(message)) query.setType(DecisionContextQuery.QueryType.EXECUTED_SEARCH_SCOPE);
        else if (isWhyRecommendedQuery(message)) query.setType(DecisionContextQuery.QueryType.WHY_RECOMMENDED);
        else if (isCurrentCriteriaQuery(message)) query.setType(DecisionContextQuery.QueryType.CURRENT_CRITERIA);
        else query.setType(DecisionContextQuery.QueryType.CONSTRAINT_PROVENANCE);
        if (query.getType() == DecisionContextQuery.QueryType.WHY_RECOMMENDED
                && context.getContextRewrite() != null
                && context.getContextRewrite().getResolvedReferences() != null
                && !context.getContextRewrite().getResolvedReferences().isEmpty()) {
            query.setResolvedReference(context.getContextRewrite().getResolvedReferences().get(0));
        }
        String key = constraintKey(message);
        query.setConstraintKey(key);
        if (key != null && key.startsWith("preference:")) query.setPreferenceValue(key.substring("preference:".length()));
        return query;
    }

    private ChatMessageResponse executeBusinessFollowUp(ChatProcessingContext context) {
        ChatMessageResponse response = newResponse(context, "BUSINESS_FOLLOW_UP");
        Long sessionId = resolveFollowUpSessionId(context.getChatId(), context.getChatSession(), context.getActiveDecisionSessionId());
        if (sessionId == null) {
            response.setAnswer("没有找到可关联的推荐会话。请告诉我店名，或重新说明用餐需求。");
            recordTurn(context.getChatId(), context.getOriginalMessage(), response);
            return response;
        }
        DecisionResponse decision = decisionService.getDecision(sessionId);
        if (!"COMPLETED".equals(decision.getStatus())) {
            response.setDecisionSessionId(sessionId); response.setDecisionStatus(decision.getStatus());
            response.setAnswer("刚才的推荐仍在等待补充条件，完成推荐后才能查询具体商户的评价、优惠券或备选。");
            recordTurn(context.getChatId(), context.getOriginalMessage(), response);
            return response;
        }
        AgentConversationRequest followUp = new AgentConversationRequest();
        followUp.setMessage(context.getEffectiveMessage());
        PolicyDecision policy = policyDecisionEngine == null ? null : policyDecisionEngine.decideFollowUp(context.getEffectiveMessage());
        recordPolicy(context.getChatSession(), context.getChatId(), sessionId, policy);
        applyPolicy(response, policy);
        AgentSessionContext agentContext = conversationStateService.agentContext(context.getChatSession());
        if (agentContext != null && context.getContextRewrite() != null) {
            agentContext.setReferenceIntents(context.getContextRewrite().getReferenceIntents());
            agentContext.setReferenceIntentMessage(context.getOriginalMessage());
            agentContext.setResolvedReferences(context.getContextRewrite().getResolvedReferences());
        }
        response.setConversation(context.getEventConsumer() == null
                ? conversationService.converse(sessionId, followUp, agentContext)
                : conversationService.converse(sessionId, followUp, agentContext, context.getEventConsumer()));
        conversationStateService.applyAgentContext(context.getChatSession(), sessionId, agentContext);
        response.setDecisionSessionId(sessionId); response.setDecisionStatus(decision.getStatus());
        response.setAnswer(response.getConversation().getAnswer());
        recordTurn(context.getChatId(), context.getOriginalMessage(), response);
        return response;
    }

    private ChatMessageResponse executeExitDecision(ChatProcessingContext context) {
        if (context.getActiveDecision() != null && isPausedDecision(context.getActiveDecision())) {
            DecisionFollowUpRequest followUp = new DecisionFollowUpRequest();
            followUp.setMessage(context.getOriginalMessage());
            decisionService.continueDecision(context.getActiveDecisionSessionId(), followUp);
        }
        if (context.getActiveDecision() != null) conversationStateService.clearActiveDecision(context.getChatSession());
        ChatMessageResponse response = executeGeneralChat(context, "EXIT_DECISION");
        response.setDecisionSessionId(null);
        return response;
    }

    private ChatMessageResponse executeGeneralChat(ChatProcessingContext context, String route) {
        ChatMessageResponse response = newResponse(context, route);
        if ("GENERAL_CHAT".equals(route) && context.getActiveDecision() != null) {
            response.setDecisionSessionId(context.getActiveDecisionSessionId());
            response.setDecisionStatus(context.getActiveDecision().getStatus());
        }
        response.setAnswer(generalReply(context.getOriginalMessage(), context.getChatHistory(),
                conversationStateService.workingMemory(context.getChatSession()), context.getTextDeltaConsumer()));
        if (!aiProperties.isConfigured()) {
            response.setUsedModel(false);
            response.setDegradedReason("模型服务未配置，本次使用本地对话降级回复。");
            log.warn("[AI][chat] action=GENERAL_CHAT event=MODEL_NOT_CONFIGURED");
        }
        recordTurn(context.getChatId(), context.getOriginalMessage(), response);
        return response;
    }

    private ChatMessageResponse newResponse(ChatProcessingContext context, String route) {
        ChatMessageResponse response = new ChatMessageResponse();
        response.setChatId(context.getChatId()); response.setRoute(route);
        response.setUsedModel(context.isUsedModel()); response.setContextRewrite(context.getContextRewrite());
        return response;
    }

    private com.hmdp.ai.service.pipeline.ChatProcessingAction toProcessingAction(String route) {
        if ("START_DECISION".equals(route)) return com.hmdp.ai.service.pipeline.ChatProcessingAction.START_DECISION;
        if ("DECISION_CONTEXT_QUERY".equals(route)) return com.hmdp.ai.service.pipeline.ChatProcessingAction.DECISION_CONTEXT_QUERY;
        if ("BUSINESS_FOLLOW_UP".equals(route)) return com.hmdp.ai.service.pipeline.ChatProcessingAction.BUSINESS_FOLLOW_UP;
        if ("EXIT_DECISION".equals(route)) return com.hmdp.ai.service.pipeline.ChatProcessingAction.EXIT_DECISION;
        if ("EXPLAIN_SUSPENDED_DECISION".equals(route)) return com.hmdp.ai.service.pipeline.ChatProcessingAction.EXPLAIN_SUSPENDED;
        return com.hmdp.ai.service.pipeline.ChatProcessingAction.GENERAL_CHAT;
    }


    private List<Long> refinementExclusions(ContextRewriteResult contextRewrite,
                                            com.hmdp.ai.dto.ConversationWorkingMemory memory,
                                            com.hmdp.ai.dto.DecisionConstraints extracted) {
        List<Long> excluded = new ArrayList<Long>();
        boolean critique = extracted != null
                && ((extracted.getBudgetDirection() != null && extracted.getBudgetDirection() != 0)
                || (extracted.getRadiusDirection() != null && extracted.getRadiusDirection() != 0));
        boolean refinement = critique || isSearchRefinement(
                contextRewrite == null ? null : contextRewrite.getOriginalQuery(),
                contextRewrite == null ? null : contextRewrite.getRewrittenQuery());
        if (!refinement || memory == null) return excluded;
        List<Long> shown = conversationStateService.shownShopIds(memory);
        if (!shown.isEmpty()) {
            excluded.addAll(shown);
            return excluded;
        }
        for (com.hmdp.ai.dto.DecisionRecommendation candidate : conversationStateService.latestCandidatePool(memory)) {
            if (candidate.getShopId() != null) excluded.add(candidate.getShopId());
        }
        return excluded;
    }

    private ChatMessageResponse buildDecisionResponse(String chatId, String originalMessage, AiChatSession state, boolean usedModel,
                                                       ContextRewriteResult contextRewrite,
                                                       DecisionResponse decision, PolicyDecision policy) {
        ChatMessageResponse response = new ChatMessageResponse();
        response.setChatId(chatId);
        response.setRoute("START_DECISION");
        response.setUsedModel(usedModel);
        response.setDecision(decision);
        response.setDecisionSessionId(decision.getSessionId());
        response.setDecisionStatus(decision.getStatus());
        response.setAnswer(decision.getAnswer() == null ? decision.getQuestion() : decision.getAnswer());
        response.setContextRewrite(contextRewrite);
        applyPolicy(response, policy);
        recordTurn(chatId, originalMessage, response);
        return response;
    }

    private ContextRewriteResult rewriteContext(String message, List<Map<String, Object>> history,
                                                AiChatSession state, Long activeSessionId) {
        if (contextRewriter == null || conversationService == null) {
            return ContextRewriteResult.unchanged(message, "REWRITER_UNAVAILABLE");
        }
        Long sessionId = activeSessionId == null ? state.getLastDecisionSessionId() : activeSessionId;
        if (sessionId == null) return ContextRewriteResult.unchanged(message, "NO_DECISION_CONTEXT");
        AgentSessionContext context = conversationStateService.agentContext(state);
        boolean hasDecisionContext = activeSessionId != null || state.getLastDecisionSessionId() != null;
        return contextRewriter.rewrite(message, history, context, hasDecisionContext);
    }

    private String route(String message, String decisionStatus, List<Map<String, Object>> chatHistory) {
        if (!aiProperties.isConfigured()) return fallbackRoute(message, decisionStatus);
        try {
            List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
            messages.add(message("system", "你是消费决策 Agent 的对话路由器。当前业务只支持餐饮商户的消费决策。根据用户最新一句话选择唯一路由：GENERAL_CHAT=普通闲聊、能力问答、非餐饮需求、需求不完整，或无法归类到其他路由；START_DECISION=用户明确要求新餐饮推荐（找餐厅/吃饭/菜品/订餐），或在同一句中同时出现可信的命名目的地（城市、行政区、商圈、地标）与餐饮消费/推荐意图，即使没有指定菜系或预算也必须开始广泛推荐；BUSINESS_FOLLOW_UP=围绕已推荐的具体餐饮商户追问评价、优惠券、营业时间、排队、地址或备选比较，对象是候选池中的某一家店；EXIT_DECISION=用户明确结束或放弃本次餐饮推荐；EXPLAIN_SUSPENDED_DECISION=用户询问当前无结果/暂停推荐的原因或下一步如何处理，该路由不发起新搜索也不查询商户详情。领域边界：没有命名目的地且只有‘附近有啥’、‘有什么推荐’、‘有没有地方推荐’这类未说明餐饮意图的句子必须是 GENERAL_CHAT，先自然追问想找什么，不能擅自开始餐饮检索；‘北京天气’、‘朋友刚从北京回来’、‘北京有哪些景点’等城市名但非餐饮消费意图仍是 GENERAL_CHAT。游泳、健身、运动场馆、医院、景点、住宿、交通等即使包含‘附近’也必须是 GENERAL_CHAT，绝不能进入餐饮推荐。上下文边界：餐饮上下文中‘福州有什么吃的’后接‘鼓楼呢？’可以是 START_DECISION 的地点 refinement；旅游上下文中‘帮我选个地方去旅游’后接‘福建省内呢？’必须是 GENERAL_CHAT。行政实体本身不等于餐饮意图。"));
            messages.add(message("system", "当前决策状态=" + decisionStatus));
            messages.add(message("system", "反偏置：当用户表述指向更换需求、换品类或重新开始时，忽略对话历史里旧推荐结果的倾向，选择 START_DECISION，不要把它当成追问候选池。边界示例：'看看有没有别的吃的'→START_DECISION；'这家店评价怎么样'→BUSINESS_FOLLOW_UP；'换一家餐厅'→START_DECISION；'算了不吃了'→EXIT_DECISION；'这家店几点关门'→BUSINESS_FOLLOW_UP。"));
            messages.add(message("system", "只读上下文查询边界：'为什么推荐这家/第一家'、'为什么第二家排这么前'、'我前面有说过安静吗'、'这个预算是我说的吗'、'现在按哪些要求帮我找'都选择 DECISION_CONTEXT_QUERY；这家几点关门、优惠券、评价、地址等商户事实仍选择 BUSINESS_FOLLOW_UP。DECISION_CONTEXT_QUERY 不发起搜索、不修改决策状态。"));
            messages.addAll(chatHistory);
            messages.add(message("user", message));
            JsonNode result = aiProperties.getRouting() != null && aiProperties.getRouting().isConfigured()
                    ? aiClient.chatRoutingCompletion(messages, Arrays.asList(routeTool()), null)
                    : aiClient.chatCompletion(messages, Arrays.asList(routeTool()), null, "CHAT_ROUTING");
            String arguments = result.path("choices").path(0).path("message").path("tool_calls").path(0)
                    .path("function").path("arguments").asText();
            String route = objectMapper.readTree(arguments).path("route").asText(fallbackRoute(message, decisionStatus));
            log.info("[AI][chat] action=CHAT_ROUTING event=MODEL_ROUTE route={}", route);
            return route;
        } catch (Exception e) {
            log.warn("[AI][chat] action=CHAT_ROUTING event=FALLBACK errorType={}", e.getClass().getSimpleName());
            return fallbackRoute(message, decisionStatus);
        }
    }

    private String generalReply(String message, List<Map<String, Object>> chatHistory,
                                com.hmdp.ai.dto.ConversationWorkingMemory memory,
                                Consumer<String> textDeltaConsumer) {
        if (!aiProperties.isConfigured()) return "你好，我是消费决策助手。想吃饭或需要了解已推荐商户时，随时告诉我。";
        try {
            List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
            messages.add(message("system", "你是本地餐饮消费决策助手。基于对话上下文自然、简短地回答。仅支持餐厅、用餐、菜品、餐饮优惠和已推荐餐饮商户的事实查询；面对游泳、运动场馆、医疗、住宿、交通等非餐饮需求，要友好说明当前暂不具备对应数据和检索能力，不得编造或推荐餐饮商户。"));
            messages.add(message("system", "只读会话事实（不得否认或改写）：" + workingMemorySummary(memory)));
            messages.addAll(chatHistory);
            messages.add(message("user", message));
            boolean streamEligible = textDeltaConsumer != null && isSafeGeneralChatStream(message);
            String answer = streamEligible
                    ? springAiTextClient.streamText(messages, "GENERAL_CHAT", textDeltaConsumer)
                    : springAiTextClient.chatText(messages, "GENERAL_CHAT");
            if (containsUngroundedRecommendation(answer)) {
                log.warn("[AI][chat] action=GENERAL_CHAT event=UNGROUNDED_RECOMMENDATION_BLOCKED");
                return "抱歉，我不能在没有检索到本地商户数据的情况下直接列出餐厅或消费建议。你可以告诉我想吃什么、预算和地点，我会先查询餐饮数据后再推荐。";
            }
            return answer;
        } catch (Exception e) {
            log.warn("[AI][chat] action=GENERAL_CHAT event=FALLBACK errorType={}", e.getClass().getSimpleName());
            return "你好，我在。想聊聊吃什么、预算或用餐场景时，随时告诉我。";
        }
    }

    private String workingMemorySummary(com.hmdp.ai.dto.ConversationWorkingMemory memory) {
        com.hmdp.ai.dto.DecisionConstraints criteria = memory == null ? null : conversationStateService.activeCriteria(memory);
        if (criteria == null) return "尚未确认搜索条件。";
        List<String> facts = new ArrayList<>();
        if (hasText(criteria.getTargetProvince())) facts.add("已锁定目标省份=" + criteria.getTargetProvince());
        if (hasText(criteria.getTargetCity())) facts.add("已锁定目标城市=" + criteria.getTargetCity());
        if (hasText(criteria.getTargetDistrict())) facts.add("已锁定目标区县=" + criteria.getTargetDistrict());
        if (hasText(criteria.getTargetArea())) facts.add("已锁定目标区域=" + criteria.getTargetArea());
        if (hasText(criteria.getCuisine())) facts.add("菜系=" + criteria.getCuisine());
        if (criteria.getBudgetPerPerson() > 0) facts.add("预算上限=" + criteria.getBudgetPerPerson());
        List<com.hmdp.ai.dto.DecisionRecommendation> candidates = conversationStateService.latestCandidatePool(memory);
        if (!candidates.isEmpty()) {
            facts.add("当前候选店铺数=" + candidates.size());
        }
        return facts.isEmpty() ? "尚未确认搜索条件。" : String.join("；", facts) + "。";
    }

    private boolean isSafeGeneralChatStream(String message) {
        String value = message == null ? "" : message;
        // Recommendation answers must pass the post-generation grounding guard before
        // reaching users, so they intentionally remain non-streaming for now.
        String[] groundedTerms = {"推荐", "餐厅", "吃饭", "吃什么", "附近", "优惠", "券", "评价", "评论", "对比"};
        for (String term : groundedTerms) if (value.contains(term)) return false;
        return true;
    }

    private String fallbackRoute(String message, String decisionStatus) {
        boolean hasDecision = !"NONE".equals(decisionStatus);
        if (isDecisionContextQuery(message)) return "DECISION_CONTEXT_QUERY";
        if (message.contains("算了") || message.contains("不聊了") || message.contains("结束")) return hasDecision ? "EXIT_DECISION" : "GENERAL_CHAT";
        boolean dining = message.contains("吃") || message.contains("餐厅") || message.contains("饭") || message.contains("菜") || message.contains("订餐");
        if (!dining) return "GENERAL_CHAT";
        return hasDecision && "COMPLETED".equals(decisionStatus) ? "BUSINESS_FOLLOW_UP" : "START_DECISION";
    }

    private String resolveContextualFollowUpRoute(String chatId, String message, AiChatSession state,
                                                  Long activeSessionId, DecisionResponse activeDecision,
                                                  ContextRewriteResult contextRewrite) {
        Long sessionId = resolveFollowUpSessionId(chatId, state, activeSessionId);
        if (sessionId == null) return null;
        DecisionResponse decision = activeDecision;
        if (decision == null || !sessionId.equals(activeSessionId)) decision = decisionService.getDecision(sessionId);
        if (decision == null || !"COMPLETED".equals(decision.getStatus())) return null;
        if (isShopInquiry(message) || (contextRewrite != null && isShopInquiry(contextRewrite.getOriginalQuery()))) {
            log.info("[AI][chat] event=ROUTE_GUARD_MATCHED chatId={} sessionId={} route=BUSINESS_FOLLOW_UP source=CONTEXT_REWRITE query={}",
                    chatId, sessionId, compact(message));
            return "BUSINESS_FOLLOW_UP";
        }
        if (hasExplicitNewDecisionIntent(message)) return null;
        // The gateway must make this routing decision from its chat-scoped Working Memory,
        // never from the legacy decision-session context cache.
        if (!conversationService.hasCandidateReference(message, conversationStateService.agentContext(state))) return null;
        log.info("[AI][chat] event=ROUTE_GUARD_MATCHED chatId={} sessionId={} route=BUSINESS_FOLLOW_UP query={}",
                chatId, sessionId, compact(message));
        return "BUSINESS_FOLLOW_UP";
    }

    private boolean hasExplicitNewDecisionIntent(String message) {
        return message.contains("我想吃") || message.contains("想找") || message.contains("帮我找")
                || message.contains("给我推荐") || message.contains("重新推荐") || message.contains("再推荐")
                || message.contains("换个餐厅") || message.contains("换一家餐厅");
    }

    /** Routing-owned business intent checks. Rewrite only resolves references and query context. */
    private boolean isAlternativeRecommendation(String originalMessage, String effectiveMessage) {
        return isAlternativeRecommendationPhrase(originalMessage) || isAlternativeRecommendationPhrase(effectiveMessage);
    }

    private boolean isAlternativeRecommendationPhrase(String message) {
        if (message == null) return false;
        String normalized = message.replaceAll("\\s+", "");
        boolean demandSwitch = normalized.contains("换几家") || normalized.contains("换一家") || normalized.contains("换一批")
                || normalized.contains("再推荐几家") || normalized.contains("多推荐几家") || normalized.contains("再来几家")
                || normalized.contains("还有别的")
                || normalized.contains("还有其他") || normalized.contains("换个条件")
                // #34：换需求表述族——"看看有没有别的吃的"此前规则全 miss 落到 LLM 被误判 BUSINESS_FOLLOW_UP。
                || normalized.contains("有没有别的") || normalized.contains("有别的")
                || normalized.contains("别的吃的") || normalized.contains("别的店") || normalized.contains("别的菜")
                || normalized.contains("其他吃的") || normalized.contains("换点别的") || normalized.contains("换个别的")
                || normalized.contains("换换");
        if (!demandSwitch) return false;
        // 防 ShopInquiry 碰撞（第 5 处双真相的规则层防御）：聚焦商户的"这家店还有没有别的评价/优惠/营业时间"
        // 是商户追问（BUSINESS_FOLLOW_UP）而非换需求，含这些词的一律不判 demand switch。
        String[] shopInquiryStrongWords = {"评价", "优惠", "代金券", "团购", "营业", "排队", "地址", "预约"};
        for (String word : shopInquiryStrongWords) {
            if (normalized.contains(word)) return false;
        }
        return true;
    }

    private boolean isSearchRefinement(String originalMessage, String effectiveMessage) {
        if (isAlternativeRecommendation(originalMessage, effectiveMessage)) return true;
        String text = ((originalMessage == null ? "" : originalMessage) + " "
                + (effectiveMessage == null ? "" : effectiveMessage)).replaceAll("\\s+", "");
        return text.contains("太贵") || text.contains("便宜点") || text.contains("更便宜")
                || text.contains("好贵") || text.contains("平价") || text.contains("实惠") || text.contains("有点贵") || text.contains("贵一点")
                || text.contains("换个条件") || text.contains("换个口味") || text.contains("换个商圈")
                || text.contains("改找") || text.contains("再加上") || text.contains("加上") || text.contains("再找")
                || text.contains("更近") || text.contains("附近一点") || text.contains("重新筛选")
                || text.contains("重新推荐") || text.contains("当前设备附近搜索") || text.contains("我附近")
                || text.contains("当前位置") || text.contains("当前定位");
    }

    private boolean isShopInquiry(String message) {
        if (message == null) return false;
        return message.contains("评价") || message.contains("评论") || message.contains("口碑")
                || message.contains("优惠") || message.contains("代金券") || message.contains("团购")
                || message.contains("营业时间") || message.contains("排队") || message.contains("地址")
                || message.contains("预约") || message.contains("第一家") || message.contains("第二家")
                || message.contains("第三家") || message.contains("这家") || message.contains("那家")
                // 距离/折扣高频确定性表述（审计 run78 落 MODEL 的 turn 补齐）：过去/距离/打折
                || message.contains("多远") || message.contains("距离") || message.contains("几公里")
                || message.contains("走过去") || message.contains("打折") || message.contains("重口")
                || message.contains("清淡") || message.contains("味道") || message.contains("口味")
                || message.contains("辣不辣") || message.contains("好吃");
    }

    private RoutingDecisionAssessment assessRouting(ChatProcessingContext context, boolean afterRewrite) {
        RoutingDecisionAssessment assessment = new RoutingDecisionAssessment();
        String message = context.getOriginalMessage() == null ? "" : context.getOriginalMessage();
        String effective = context.getEffectiveMessage() == null ? message : context.getEffectiveMessage();
        DecisionResponse decision = context.getActiveDecision();
        assessment.setStateAllowed(true);
        if (context.getRequest() != null && context.getRequest().getSelectedOptionId() != null) {
            assessment.setCandidateAction(com.hmdp.ai.service.pipeline.ChatProcessingAction.DECISION_EVENT);
            assessment.setSource("COMMAND");
            assessment.setStateAllowed(isPausedDecision(decision));
            assessment.setShouldEscalate(!assessment.isStateAllowed());
            assessment.setReason("selected_option_command");
            return assessment;
        }
        boolean reference = isReferenceMessage(message);
        boolean conflict = ((isShopInquiry(message) || reference) && isAlternativeRecommendation(message, effective))
                || (message.contains("先看看") && isAlternativeRecommendation(message, effective));
        assessment.setConflictDetected(conflict);
        if (conflict) {
            assessment.setSource("RULE"); assessment.setShouldEscalate(true); assessment.setReason("competing_actions");
            return assessment;
        }
        if (isDecisionContextQuery(effective)) {
            assessment.setCandidateAction(com.hmdp.ai.service.pipeline.ChatProcessingAction.DECISION_CONTEXT_QUERY);
            assessment.setSource("RULE");
            boolean requiresReference = isReferenceMessage(message);
            assessment.setContextRequired(requiresReference);
            assessment.setContextResolved(!requiresReference);
            assessment.setRequiredContextMissing(requiresReference);
            assessment.setReason("decision_context_query");
            return assessment;
        }
        if (isAlternativeRecommendation(message, effective) || isNewRecommendationIntent(effective)) {
            assessment.setCandidateAction(com.hmdp.ai.service.pipeline.ChatProcessingAction.START_DECISION);
            assessment.setSource("RULE"); assessment.setReason("explicit_recommendation_or_alternative");
            return assessment;
        }
        if (isPausedDecision(decision) && (refersToCurrentDeviceLocation(message) || message.contains("附近呢"))) {
            assessment.setCandidateAction(com.hmdp.ai.service.pipeline.ChatProcessingAction.START_DECISION);
            assessment.setSource("CONTEXT");
            assessment.setContextRequired(true);
            assessment.setContextResolved(false);
            assessment.setRequiredContextMissing(true);
            assessment.setReason("current_device_location_resolution");
            return assessment;
        }
        boolean completed = decision != null && "COMPLETED".equals(decision.getStatus());
        if ((completed || reference) && (isShopInquiry(message) || reference)
                && (reference || !isSearchRefinement(message, effective))) {
            assessment.setCandidateAction(com.hmdp.ai.service.pipeline.ChatProcessingAction.BUSINESS_FOLLOW_UP);
            assessment.setSource(afterRewrite ? "CONTEXT" : "RULE");
            assessment.setContextRequired(reference);
            assessment.setContextResolved(afterRewrite && !message.equals(effective));
            assessment.setRequiredContextMissing(assessment.isContextRequired() && !assessment.isContextResolved());
            assessment.setShouldEscalate(false);
            assessment.setReason(reference ? "shop_reference_resolution" : "explicit_shop_inquiry");
            return assessment;
        }
        if (isNewRecommendationIntent(effective) || isSearchRefinement(message, effective)) {
            assessment.setCandidateAction(com.hmdp.ai.service.pipeline.ChatProcessingAction.START_DECISION);
            assessment.setSource("RULE"); assessment.setReason("deterministic_recommendation_rule");
            return assessment;
        }
        assessment.setSource("MODEL"); assessment.setShouldEscalate(true); assessment.setReason("no_unique_action_candidate");
        return assessment;
    }

    private String validateModelRoute(String route, DecisionResponse decision, String message) {
        if (!isRouteAllowed(route, decision)) return fallbackRoute(message == null ? "" : message, decision == null ? "NONE" : decision.getStatus());
        return route;
    }

    private boolean isRouteAllowed(String route, DecisionResponse decision) {
        if (route == null) return false;
        if ("EXPLAIN_SUSPENDED_DECISION".equals(route)) return isSuspendedDecision(decision);
        if ("DECISION_CONTEXT_QUERY".equals(route)) return true;
        if ("BUSINESS_FOLLOW_UP".equals(route)) return decision != null && "COMPLETED".equals(decision.getStatus());
        if ("EXIT_DECISION".equals(route)) return true;
        return "GENERAL_CHAT".equals(route) || "START_DECISION".equals(route);
    }

    private boolean isNewRecommendationIntent(String message) {
        if (message == null || message.isEmpty() || isFocusedShopQuestion(message)) return false;
        boolean asksForPlace = message.contains("店") || message.contains("餐厅") || message.contains("餐馆")
                || message.contains("吃饭");
        boolean asksForNewOptions = message.contains("有没有") || message.contains("有没") || message.contains("还有")
                || message.contains("有什么") || message.contains("推荐") || message.contains("找") || message.contains("来一家");
        boolean hasScene = message.contains("适合约会") || message.contains("约会") || message.contains("聚餐")
                || message.contains("安静") || message.contains("清淡") || message.contains("性价比");
        boolean hasDiningCategory = hasDiningCategory(message);
        boolean hasDiningIntent = hasDiningIntentEvidence(message);
        boolean refinement = message.contains("换成") || message.contains("改成") || message.contains("便宜点")
                || message.contains("贵点") || message.contains("不要辣") || message.contains("不吃辣");
        boolean explicitSearchVerb = message.contains("帮我找") || message.contains("帮我搜") || message.contains("找一下")
                || message.contains("推荐一下") || message.contains("给我推荐") || message.contains("推荐一家")
                || message.contains("想吃") || message.contains("想喝");
        boolean mealPlanChanged = message.contains("取消") || message.contains("改吃") || message.contains("我自己")
                || message.contains("一个人吃") || message.contains("单人");
        boolean locationSearchContinuation = message.contains("附近")
                && (message.contains("找") || message.contains("推荐") || message.contains("看看") || message.contains("有什么"))
                // 领域守卫：仅"附近+看看"不足以判定餐饮推荐，需同时带餐饮语义（店/餐厅/吃饭 或 菜系 或餐饮信号），
                // 避免"帮我看看附近有没有羽毛球馆"被正向规则短路误判 START_DECISION（2026-09-02 修复 #29）
                && hasDiningIntent;
        // "附近的火锅"：附近+菜系无动词，词表补充（case32 审计）
        boolean nearbyCategory = message.contains("附近") && hasDiningCategory;
        boolean diningQuestion = hasDiningSignal(message) && asksForNewOptions;
        return hasDiningIntent && ((asksForPlace && (asksForNewOptions || hasScene || hasDiningCategory))
                || (hasDiningCategory && (explicitSearchVerb || mealPlanChanged)) || (refinement && hasDiningCategory)
                || locationSearchContinuation || nearbyCategory || diningQuestion);
    }

    private boolean hasDiningIntentEvidence(String message) {
        return message != null && (message.contains("店") || message.contains("餐厅") || message.contains("餐馆")
                || message.contains("吃饭") || hasDiningSignal(message) || hasDiningCategory(message));
    }

    private boolean hasDiningCategory(String message) {
        return message != null && (message.contains("烤肉") || message.contains("烧烤") || message.contains("火锅")
                || message.contains("日料") || message.contains("料理") || message.contains("小吃") || message.contains("咖啡")
                || message.contains("奶茶") || message.contains("川菜") || message.contains("粤菜")
                || message.contains("闽菜") || message.contains("湘菜") || message.contains("江浙菜")
                || message.contains("本帮菜") || message.contains("西餐") || message.contains("韩餐")
                || message.contains("简餐") || message.contains("快餐"));
    }

    private boolean isFocusedShopQuestion(String message) {
        return message.contains("这家") || message.contains("那家") || message.contains("这个") || message.contains("这一个")
                || message.contains("上一家") || message.contains("刚才那家") || message.contains("刚才那个")
                || message.contains("上一轮那个");
    }

    private boolean refersToCurrentDeviceLocation(String message) {
        return message.contains("当前定位") || message.contains("当前位置") || message.contains("现在位置")
                || message.contains("我这里") || message.contains("我身边");
    }

    private boolean isPausedDecision(DecisionResponse decision) {
        return decision != null && ("CLARIFYING".equals(decision.getStatus())
                || "WAITING_RELAXATION".equals(decision.getStatus())
                || "ZERO_RESULT_NO_DATA".equals(decision.getStatus()));
    }

    private boolean isSuspendedDecision(DecisionResponse decision) {
        return decision != null && ("WAITING_RELAXATION".equals(decision.getStatus())
                || "ZERO_RESULT_NO_DATA".equals(decision.getStatus()));
    }

    private boolean isSuspendedDecisionMetaQuestion(String message) {
        String normalized = message == null ? "" : message.replaceAll("\\s+", "");
        String[] metaTerms = {"放宽", "什么条件", "什么意思", "怎么回事", "为什么", "不是说", "不是已经", "什么东西"};
        for (String term : metaTerms) if (normalized.contains(term)) return true;
        return false;
    }

    /**
     * 纯位置恢复表达：只含位置指代（我附近/当前位置/当前定位/就在我这），不含餐饮或需求词。
     * 用于 WAITING_RELAXATION 态"保留约束换位置重搜"，与"福州附近的火锅"（显式新需求）区分。
     * 边界（已测试钉死）：
     *   "我附近" → 恢复；"我附近有没有火锅"（含餐饮词）→ START_DECISION 重开。
     */
    private boolean isLocationRecoveryExpression(String message) {
        if (message == null) return false;
        String normalized = message.replaceAll("\\s+", "");
        if (normalized.isEmpty()) return false;
        boolean hasLocationTerm = normalized.contains("我附近") || normalized.contains("当前位置")
                || normalized.contains("当前定位") || normalized.contains("就在我这")
                || normalized.contains("在我这") || normalized.contains("用我的位置");
        if (!hasLocationTerm) return false;
        String[] diningOrDemandTerms = {"火锅", "烧烤", "日料", "小吃", "川菜", "粤菜", "菜", "面", "饭",
                "找", "吃", "推荐", "餐", "店", "预算", "便宜", "评分", "评价", "换一家", "换一批"};
        for (String term : diningOrDemandTerms) {
            if (normalized.contains(term)) return false;
        }
        return true;
    }

    private ChatMessageResponse explainSuspendedDecision(String chatId, String message, AiChatSession state,
                                                         Long activeSessionId,
                                                         DecisionResponse decision) {
        ChatMessageResponse response = new ChatMessageResponse();
        response.setChatId(chatId);
        response.setRoute("EXPLAIN_SUSPENDED_DECISION");
        response.setUsedModel(false);
        response.setDecision(decision);
        response.setDecisionSessionId(activeSessionId);
        response.setDecisionStatus(decision.getStatus());
        String canonicalLocationName = null;
        if (conversationStateService != null && state != null) {
            ConversationLocationSlot searchLocation = conversationStateService.searchLocation(
                    conversationStateService.workingMemory(state));
            canonicalLocationName = searchLocation == null ? null : searchLocation.getCanonicalName();
        }
        response.setAnswer(failureExplanationFormatter.format(decision, canonicalLocationName));
        log.info("[AI][chat] event=SUSPENDED_DECISION_EXPLAINED chatId={} sessionId={} status={} query={}",
                chatId, activeSessionId, decision.getStatus(), compact(message));
        recordTurn(chatId, message, response);
        return response;
    }

    /** Compatibility overload for direct unit tests and callers without a session snapshot. */
    private ChatMessageResponse explainSuspendedDecision(String chatId, String message, Long activeSessionId,
                                                         DecisionResponse decision) {
        return explainSuspendedDecision(chatId, message, null, activeSessionId, decision);
    }

    private boolean isWaitingRelaxation(DecisionResponse decision) {
        return decision != null && "WAITING_RELAXATION".equals(decision.getStatus());
    }

    private com.hmdp.ai.dto.DecisionConstraints activeCriteriaForTurn(ChatProcessingContext context) {
        if (context == null || context.getWorkingMemory() == null || conversationStateService == null) return null;
        return conversationStateService.activeCriteria(context.getWorkingMemory());
    }

    /** A paused recommendation may be refined after unrelated small talk. */
    private boolean isContinuationRefinement(String message, AiChatSession state) {
        if (message == null || !message.contains("继续")) return false;
        com.hmdp.ai.dto.DecisionConstraints criteria = conversationStateService.activeCriteria(conversationStateService.workingMemory(state));
        if (criteria == null) return false;
        boolean hasActiveDemand = hasText(criteria.getCuisine()) || hasText(criteria.getKeyword())
                || hasText(criteria.getTargetProvince()) || hasText(criteria.getTargetCity()) || hasText(criteria.getTargetDistrict()) || Boolean.TRUE.equals(criteria.getNearby());
        boolean hasRefinement = message.contains("安静") || message.contains("便宜") || message.contains("更近")
                || message.contains("换") || message.contains("清淡") || message.contains("推荐") || message.contains("找");
        return hasActiveDemand && hasRefinement;
    }

    private boolean isLocationClarification(DecisionResponse decision) {
        return decision != null && "CLARIFYING".equals(decision.getStatus());
    }

    private boolean isPotentialNamedLocation(String message) {
        String normalized = message == null ? "" : message.replaceAll("\\s+", "").trim();
        if (normalized.length() < 2 || normalized.length() > 24 || normalized.contains("?") || normalized.contains("？")) return false;
        String[] nonLocationWords = {"这家", "那家", "第一家", "第二家", "评价", "优惠", "代金券", "团购", "营业", "几点",
                "怎么样", "对比", "推荐", "找", "吃", "餐", "店", "火锅", "烧烤", "日料", "小吃", "算了", "结束", "不找了"};
        for (String word : nonLocationWords) {
            if (normalized.contains(word)) return false;
        }
        return normalized.matches("^(?:我在|去|到)?[\\p{IsHan}]{2,16}(?:省|市|区|县|镇|乡|街道|大学城|商圈)?$");
    }

    /** Extracts a user-provided POI name only in an active location clarification turn. */
    private String extractClarificationPoiQuery(String message) {
        if (!hasText(message)) return null;
        String value = message.trim().replaceAll("\\s+", "");
        boolean explicitPrefix = false;
        String[] prefixes = {"我说的是", "我指的是", "指的是", "就是", "是"};
        for (String prefix : prefixes) {
            if (value.startsWith(prefix) && value.length() > prefix.length()) {
                value = value.substring(prefix.length());
                explicitPrefix = true;
                break;
            }
        }
        value = value.replaceAll("[，。！？,.!?]+$", "");
        if (value.length() < 2 || value.length() > 24 || value.contains("附近") || value.contains("周边")) return null;
        if (value.matches(".*(什么|哪里|怎么|有没有|吃的|餐厅|餐馆|推荐).*") || value.matches(".*[?？].*")) return null;
        if (!explicitPrefix && !isPotentialNamedLocation(value)) return null;
        if (!explicitPrefix && value.matches(".*(换一家|换个|再来一家|结束|算了|不找了).*")) return null;
        return value.matches("^[\\p{IsHan}A-Za-z0-9·()（）-]{2,24}$") ? value : null;
    }

    /** A free-text confirmation may select a pending canonical candidate without a new provider call. */
    private int uniquePendingCandidateIndex(String message, AiChatSession state) {
        if (state == null || conversationStateService == null || !hasText(message)) return -1;
        ConversationSlots pendingSlots = conversationStateService.slots(state);
        if (pendingSlots == null) return -1;
        List<ResolvedLocationCandidate> candidates = pendingSlots.getPendingLocationCandidates();
        if (candidates == null || candidates.isEmpty()) return -1;
        String text = message.replaceAll("\\s+", "");
        List<Integer> matches = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            ResolvedLocationCandidate candidate = candidates.get(index);
            if (candidate == null) continue;
            String canonical = normalizeCandidateText(candidate.getCanonicalName());
            String campus = normalizeCandidateText(candidate.getCampusLabel());
            String label = normalizeCandidateText(candidate.getLabel());
            if ((hasText(canonical) && text.contains(canonical))
                    || (hasText(campus) && text.contains(campus))
                    || (hasText(label) && text.contains(label))) {
                matches.add(index);
            }
        }
        return matches.size() == 1 ? matches.get(0) : -1;
    }

    private String normalizeCandidateText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
    }

    private ChatMessageResponse resolveNamedLocation(String chatId, String message, AiChatSession state,
                                                     Long activeSessionId, DecisionResponse activeDecision) {
        return buildLocationResolutionResponse(chatId, message, state, activeSessionId, activeDecision, message);
    }

    private ChatMessageResponse buildLocationResolutionResponse(String chatId, String message, AiChatSession state,
                                                                Long activeSessionId, DecisionResponse activeDecision,
                                                                String locationQuery) {
        return buildLocationResolutionResponse(chatId, message, state, activeSessionId, activeDecision, locationQuery, null);
    }

    private ChatMessageResponse buildLocationResolutionResponse(String chatId, String message, AiChatSession state,
                                                                Long activeSessionId, DecisionResponse activeDecision,
                                                                String locationQuery, String requestedEntityTypeHint) {
        if (!locationServiceAvailable()) {
            activeDecision.setQuestion("已识别到你指定的地点“" + locationQuery
                    + "”，但当前地图解析服务不可用，无法安全将它替换为坐标。请提供该地点附近的经纬度后继续搜索。"
            );
            activeDecision.setAnswer(null);
            activeDecision.getOptions().clear();
            activeDecision.getOptions().add(new com.hmdp.ai.dto.DecisionOption("PROVIDE_LOCATION", "提交当前位置坐标后继续"));
            activeDecision.getOptions().add(new com.hmdp.ai.dto.DecisionOption("END_DECISION", "结束本次推荐"));
            ChatMessageResponse response = new ChatMessageResponse();
            response.setChatId(chatId);
            response.setRoute("LOCATION_RESOLUTION");
            response.setUsedModel(false);
            response.setDecision(activeDecision);
            response.setDecisionSessionId(activeSessionId);
            response.setDecisionStatus(activeDecision.getStatus());
            response.setAnswer(activeDecision.getQuestion());
            log.warn("[AI][chat] event=LOCATION_RESOLUTION_UNAVAILABLE chatId={} sessionId={} query={}",
                    chatId, activeSessionId, compact(locationQuery));
            recordTurn(chatId, message, response);
            return response;
        }
        String normalizedLocationQuery = normalizeLocationQuery(locationQuery);
        String clarificationPoi = extractClarificationPoiQuery(locationQuery);
        if (isLocationClarification(activeDecision) && hasText(clarificationPoi)) {
            normalizedLocationQuery = clarificationPoi;
        }
        LocationResolutionContext resolutionContext = locationResolutionContext(state);
        enrichAdministrativeContext(normalizedLocationQuery, resolutionContext, state);
        com.hmdp.ai.dto.DecisionConstraints activeCriteria = conversationStateService.activeCriteria(
                conversationStateService.workingMemory(state));
        // The structured targetArea is the POI authority. The raw turn is only
        // used above to discover an explicit administrative prefix such as
        // “北京农大”; polite wording must never become a POI keyword.
        boolean explicitClarificationOverride = isLocationClarification(activeDecision)
                && hasText(clarificationPoi);
        String poiQuery = !explicitClarificationOverride && activeCriteria != null && hasText(activeCriteria.getTargetArea())
                ? activeCriteria.getTargetArea() : normalizedLocationQuery;
        String persistedHint = activeCriteria == null ? null : activeCriteria.getEntityTypeHint();
        String entityTypeHint = hasText(requestedEntityTypeHint) ? requestedEntityTypeHint
                : hasText(persistedHint) && !"UNKNOWN".equalsIgnoreCase(persistedHint)
                ? persistedHint : "UNKNOWN";
        if (hasText(resolutionContext.getActiveCity())) {
            String city = resolutionContext.getActiveCity();
            String cityAlias = city.endsWith("市") ? city.substring(0, city.length() - 1) : city;
            if (poiQuery.startsWith(city)) poiQuery = poiQuery.substring(city.length()).trim();
            else if (!cityAlias.isEmpty() && poiQuery.startsWith(cityAlias)) poiQuery = poiQuery.substring(cityAlias.length()).trim();
            if (poiQuery.isEmpty() && activeCriteria != null && hasText(activeCriteria.getTargetArea())) {
                poiQuery = activeCriteria.getTargetArea();
            }
        }
        List<ResolvedLocationCandidate> candidates = locationResolutionService.resolve(
                new LocationResolutionRequest(poiQuery, resolutionContext, entityTypeHint));
        if (candidates.isEmpty()) {
            log.info("[AI][chat] event=LOCATION_RESOLUTION_EMPTY chatId={} sessionId={} query={}",
                    chatId, activeSessionId, compact(poiQuery));
            return null;
        }
        DecisionResponse decision = activeDecision;
        // A nationwide short-name recall is useful for detecting ambiguity, but
        // must never be rendered as if all candidates shared one search scope.
        if (!hasGeographicContext(resolutionContext) && candidates.size() > 1) {
            conversationStateService.rememberLocationCandidates(state, List.of());
            String question = hasMultipleCandidateCities(candidates)
                    ? "“" + poiQuery + "”在不同城市都有。你可以告诉我城市，或者允许我使用当前位置帮你判断。"
                    : "我暂时无法从全国结果确定“" + poiQuery + "”具体指哪个地点。你可以告诉我城市，或者允许我使用当前位置帮你判断。";
            decision.setQuestion(question);
            decision.setAnswer(null);
            decision.getOptions().clear();
            decision.getOptions().add(new com.hmdp.ai.dto.DecisionOption(
                    "USE_DEVICE_LOCATION_FOR_POI_DISAMBIGUATION", "使用当前位置判断地点"));
            decision.getOptions().add(new com.hmdp.ai.dto.DecisionOption("END_DECISION", "结束本次推荐"));
            ChatMessageResponse response = new ChatMessageResponse();
            response.setChatId(chatId);
            response.setRoute("LOCATION_RESOLUTION");
            response.setUsedModel(false);
            response.setDecision(decision);
            response.setDecisionSessionId(activeSessionId);
            response.setDecisionStatus(decision.getStatus());
            response.setAnswer(decision.getQuestion());
            log.info("[AI][chat] event=LOCATION_RESOLUTION_AMBIGUOUS_NATIONWIDE chatId={} sessionId={} query={} cities={}",
                    chatId, activeSessionId, compact(poiQuery), candidateCities(candidates).size());
            recordTurn(chatId, message, response);
            return response;
        }
        conversationStateService.rememberLocationCandidates(state, candidates);
        decision.setQuestion(buildLocationConfirmationQuestion(candidates));
        decision.setAnswer(null);
        decision.getOptions().clear();
        for (int index = 0; index < candidates.size(); index++) {
            decision.getOptions().add(new com.hmdp.ai.dto.DecisionOption("CONFIRM_RESOLVED_LOCATION_" + index,
                    "使用“" + candidates.get(index).getLabel() + "”作为搜索位置"));
        }
        decision.getOptions().add(new com.hmdp.ai.dto.DecisionOption("PROVIDE_LOCATION", "改用当前位置坐标"));
        decision.getOptions().add(new com.hmdp.ai.dto.DecisionOption("DECLINE_LOCATION", "不提供位置，按全城搜索"));
        decision.getOptions().add(new com.hmdp.ai.dto.DecisionOption("END_DECISION", "结束本次推荐"));
        ChatMessageResponse response = new ChatMessageResponse();
        response.setChatId(chatId);
        response.setRoute("LOCATION_RESOLUTION");
        response.setUsedModel(false);
        response.setDecision(decision);
        response.setDecisionSessionId(activeSessionId);
        response.setDecisionStatus(decision.getStatus());
        response.setAnswer(decision.getQuestion());
        log.info("[AI][chat] event=LOCATION_RESOLUTION_CANDIDATES chatId={} sessionId={} query={} candidates={}",
                chatId, activeSessionId, compact(poiQuery), candidates.size());
        recordTurn(chatId, message, response);
        return response;
    }

    private boolean locationServiceAvailable() {
        return locationResolutionService != null && locationResolutionService.isAvailable();
    }


    private String buildLocationConfirmationQuestion(List<ResolvedLocationCandidate> candidates) {
        if (candidates.size() == 1) {
            ResolvedLocationCandidate candidate = candidates.get(0);
            return "已通过地图服务解析到“" + candidate.getLabel() + "”。确认使用该位置搜索附近餐饮商户吗？";
        }
        return "地图服务解析到多个可能地点，请选择要作为搜索中心的位置。";
    }

    private String normalizeLocationQuery(String locationQuery) {
        if (locationQuery == null) return "";
        return locationQuery.trim().replaceAll("(?:附近|周边|那边|当地|一带).*$", "");
    }

    private void recordPolicy(AiChatSession state, String chatId, Long sessionId, PolicyDecision policy) {
        if (policy == null) return;
        conversationStateService.recordPolicy(state, policy);
        if (conversationEventService != null) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("action", policy.getAction()); result.put("reason", policy.getReason());
            result.put("blocking", policy.isBlocking()); result.put("decisionSessionId", sessionId);
            conversationEventService.recordBestEffort(ConversationEventType.POLICY_DECISION, ConversationEventStatus.SUCCESS,
                    null, null, result, null);
        }
        log.info("[AI][policy] event=POLICY_DECIDED chatId={} sessionId={} action={} blocking={} reason={} scope={}",
                chatId, sessionId, policy.getAction(), policy.isBlocking(), compact(policy.getReason()),
                compact(policy.getExplicitLocationScope()));
    }

    private void applyPolicy(ChatMessageResponse response, PolicyDecision policy) {
        if (policy == null) return;
        response.setPolicyAction(policy.getAction());
        response.setPolicyReason(policy.getReason());
    }

    /** 餐饮强信号判断（原 isRestaurantSearch 死代码，现被 isNonDiningDomain 复用，2026-09-02 修复 #29）。 */
    private boolean hasDiningSignal(String message) {
        if (message == null) return false;
        String[] keywords = {"吃", "餐厅", "餐馆", "饭店", "点餐", "订餐", "外卖", "饭", "菜", "烧烤", "烤肉", "火锅", "日料",
                "料理", "小吃", "咖啡", "奶茶", "聚餐", "美食", "麻辣烫", "串串", "面条", "汉堡", "披萨", "甜品", "夜宵"};
        for (String keyword : keywords) {
            if (message.contains(keyword)) return true;
        }
        return false;
    }

    /**
     * 非餐饮领域守卫（OOS gate）：命中非餐饮领域词表且无餐饮强信号时判定为领域外需求。
     * 领域边界是确定性业务约束，不应依赖 LLM 概率判断（AGENTS.md 铁律）；
     * "无餐饮强信号"保护复合句（"打完羽毛球去吃火锅"含"吃/火锅"→ 不拦截）。
     * 词表是强信号拦截，未覆盖的新说法自然回落到 LLM 兜底。
     */
    private boolean isNonDiningDomain(String message) {
        if (message == null || hasDiningSignal(message)) return false;
        String[] outOfScope = {
                "羽毛球", "篮球", "足球", "排球", "乒乓球", "网球", "游泳", "健身", "健身房", "运动", "体育", "球场",
                "跑步", "瑜伽", "台球", "高尔夫", "滑冰", "滑雪", "攀岩", "射箭",
                "医院", "诊所", "体检", "牙科", "眼科", "药店", "挂号", "看病", "就医", "门诊", "急诊",
                "住宿", "酒店", "宾馆", "民宿", "旅馆", "客栈", "青旅",
                "打车", "出租车", "滴滴", "交通", "公交", "地铁", "高铁", "火车", "机票", "航班", "机场", "车站", "停车场", "加油",
                "景点", "景区", "公园", "游乐园", "游乐场", "动物园", "博物馆", "图书馆", "电影院", "电影", "KTV", "ktv",
                "演出", "演唱会", "密室", "剧本杀", "网吧", "电竞",
                "理发", "美容", "美甲", "洗车", "修车", "家政", "保洁", "搬家", "快递", "超市", "商场", "购物", "买衣服",
                "服装店", "家电", "手机店", "维修", "银行", "办证", "宠物", "宠物店", "拍照", "照相"
        };
        for (String token : outOfScope) {
            if (message.contains(token)) return true;
        }
        return false;
    }

    private Map<String, Object> routeTool() {
        Map<String, Object> route = new LinkedHashMap<String, Object>();
        route.put("type", "string");
        route.put("enum", Arrays.asList("GENERAL_CHAT", "START_DECISION", "DECISION_CONTEXT_QUERY", "BUSINESS_FOLLOW_UP", "EXIT_DECISION", "EXPLAIN_SUSPENDED_DECISION"));
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("route", route);
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put("type", "object"); parameters.put("properties", properties);
        parameters.put("required", Arrays.asList("route")); parameters.put("additionalProperties", false);
        Map<String, Object> function = new LinkedHashMap<String, Object>();
        function.put("name", "route_chat_message");
        function.put("description", "仅当用户明确需要餐饮推荐或已推荐餐饮商户的事实查询时，才选择餐饮决策相关路由。");
        function.put("parameters", parameters);
        Map<String, Object> tool = new LinkedHashMap<String, Object>();
        tool.put("type", "function"); tool.put("function", function);
        return tool;
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("role", role); item.put("content", content); return item;
    }

    private String compact(String value) {
        if (value == null) return "";
        String result = value.replaceAll("[\\r\\n\\t]+", " ");
        return result.length() > 800 ? result.substring(0, 800) + "..." : result;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /** A city supplied while resolving a paused short POI becomes search context,
     * never a named POI itself. */
    private void enrichAdministrativeContext(String query, LocationResolutionContext context, AiChatSession state) {
        if (!hasText(query) || context == null || hasText(context.getActiveCity()) || administrativeRegionResolver == null) return;
        AdministrativeResolution resolution = administrativeRegionResolver.resolve(query, null);
        if (resolution.status() != AdministrativeResolution.Status.RESOLVED) {
            // Short POI text may prefix an explicit city (for example, city +
            // campus nickname). Probe only bounded leading spans through the
            // administrative resolver; no POI alias table is introduced here.
            for (int end = Math.min(query.length(), 8); end >= 2; end--) {
                AdministrativeResolution prefix = administrativeRegionResolver.resolveGeographicContextPrefix(query.substring(0, end));
                if (prefix.status() == AdministrativeResolution.Status.RESOLVED && prefix.candidates().size() == 1) {
                    resolution = prefix;
                    break;
                }
            }
        }
        if (resolution.status() == AdministrativeResolution.Status.RESOLVED && resolution.candidates().size() == 1) {
            AdministrativeRegion candidate = resolution.candidates().get(0);
            if (candidate.getLevel() == com.hmdp.ai.geo.AdministrativeLevel.CITY) {
                context.setActiveProvince(candidate.getProvince());
                context.setActiveCity(candidate.getName());
                context.setActiveCityAdcode(candidate.getAdcode());
            }
        }
    }

    private boolean hasGeographicContext(LocationResolutionContext context) {
        return context != null && (hasText(context.getActiveCity()) || hasText(context.getActiveProvince())
                || context.getDeviceLatitude() != null && context.getDeviceLongitude() != null);
    }

    private boolean hasMultipleCandidateCities(List<ResolvedLocationCandidate> candidates) {
        return candidateCities(candidates).size() > 1;
    }

    private java.util.Set<String> candidateCities(List<ResolvedLocationCandidate> candidates) {
        java.util.Set<String> cities = new java.util.LinkedHashSet<>();
        if (candidates == null) return cities;
        for (ResolvedLocationCandidate candidate : candidates) {
            if (candidate != null && hasText(candidate.getCity())) cities.add(candidate.getCity().trim());
        }
        return cities;
    }

    private LocationResolutionContext locationResolutionContext(AiChatSession state) {
        LocationResolutionContext context = new LocationResolutionContext();
        com.hmdp.ai.dto.ConversationWorkingMemory memory = conversationStateService.workingMemory(state);
        ConversationLocationSlot device = conversationStateService.usableLocation(state);
        ConversationLocationSlot named = conversationStateService.searchLocation(memory);
        if (device != null) {
            context.setDeviceLatitude(device.getLatitude());
            context.setDeviceLongitude(device.getLongitude());
        }
        com.hmdp.ai.dto.DecisionConstraints criteria = conversationStateService.activeCriteria(memory);
        if (criteria != null) {
            context.setActiveProvince(criteria.getTargetProvince());
            context.setActiveCity(criteria.getTargetCity());
            context.setActiveDistrict(criteria.getTargetDistrict());
        }
        if (named != null) context.setCurrentNamedLocation(named.getCanonicalName());
        return context;
    }

    private boolean hasCoordinates(ConversationLocationSlot location) {
        return location != null && "AVAILABLE".equalsIgnoreCase(location.getStatus())
                && location.getLatitude() != null && location.getLongitude() != null;
    }

    private void recordTurn(String chatId, String userMessage, ChatMessageResponse response) {
        try {
            if (conversationEventService != null) {
            Map<String, Object> output = new LinkedHashMap<String, Object>();
            output.put("content", response.getAnswer()); output.put("route", response.getRoute());
            output.put("decisionSessionId", response.getDecisionSessionId());
                conversationEventService.persistDurableEvent(ConversationEventType.ASSISTANT_OUTPUT, ConversationEventStatus.SUCCESS,
                    null, null, output, null);
                response.setTraceIncomplete(conversationEventService.isTraceIncomplete());
            }
            // Redis is a projection of the two durable message facts, never their authority.
            chatMemoryService.appendTurn(chatId, userMessage, response.getAnswer(), response.getRoute(), response.getDecisionSessionId());
        } finally {
            if (conversationEventService != null) conversationEventService.clearTrace();
        }
        log.info("[AI][chat] event=MEMORY_SAVED chatId={} userChars={} assistantChars={}", chatId,
                userMessage.length(), response.getAnswer() == null ? 0 : response.getAnswer().length());
    }

    private ChatMessageResponse handleDecisionEvent(String chatId, String message, ChatMessageRequest request,
                                                    AiChatSession state, Long activeSessionId,
                                                    ChatMessageResponse response) {
        String optionId = request.getSelectedOptionId();
        boolean useDeviceLocationForPoiDisambiguation =
                "USE_DEVICE_LOCATION_FOR_POI_DISAMBIGUATION".equals(optionId);
        if (useDeviceLocationForPoiDisambiguation) {
            optionId = "PROVIDE_LOCATION";
        }
        DecisionResponse suspendedDecision = activeSessionId == null ? null : decisionService.getDecision(activeSessionId);
        String confirmedLocationName = null;
        boolean confirmedNamedLocation = optionId != null
                && optionId.startsWith("CONFIRM_RESOLVED_LOCATION_");
        if (optionId == null) {
            if (suspendedDecision != null && "WAITING_RELAXATION".equals(suspendedDecision.getStatus())
                    && isLocationRecoveryExpression(message)) {
                // B 修复 #case30：WAITING_RELAXATION 态自然语言"我附近" → 恢复（PROVIDE_LOCATION），保留约束换位置重搜
                optionId = "PROVIDE_LOCATION";
            }
        }
        if ("SWITCH_CITY".equals(optionId)) {
            decisionService.validateSelectedOption(activeSessionId, optionId);
            DecisionResponse decision = decisionService.getDecision(activeSessionId);
            response.setRoute("SWITCH_CITY");
            response.setDecision(decision);
            response.setDecisionSessionId(activeSessionId);
            response.setDecisionStatus(decision == null ? "ZERO_RESULT_NO_DATA" : decision.getStatus());
            response.setAnswer("请直接告诉我想切换到哪个城市，例如“帮我看看福州有什么好吃的”。");
            log.info("[AI][chat] event=NO_DATA_SWITCH_CITY_REQUESTED chatId={} sessionId={}", chatId, activeSessionId);
            recordTurn(chatId, message, response);
            return response;
        }
        if (optionId.startsWith("CONFIRM_RESOLVED_LOCATION_")) {
            // Confirm legality before accepting a candidate into durable Working Memory.
            decisionService.validateSelectedOption(activeSessionId, "PROVIDE_LOCATION");
            int index = Integer.parseInt(optionId.substring("CONFIRM_RESOLVED_LOCATION_".length()));
            ResolvedLocationCandidate candidate = conversationStateService.acceptPendingSearchLocation(state, index);
            optionId = "PROVIDE_LOCATION";
            confirmedLocationName = candidate.getCanonicalName();
            log.info("[AI][chat] event=LOCATION_RESOLUTION_CONFIRMED chatId={} sessionId={} label={} latitude={} longitude={}",
                    chatId, activeSessionId, candidate.getLabel(), candidate.getLatitude(), candidate.getLongitude());
        }
        if ("DECLINE_LOCATION".equals(optionId)) {
            decisionService.validateSelectedOption(activeSessionId, optionId);
            conversationStateService.declineLocation(state);
        }
        if (useDeviceLocationForPoiDisambiguation
                && conversationStateService.usableLocation(state) == null) {
            response.setRoute("LOCATION_RESOLUTION");
            response.setDecision(suspendedDecision);
            response.setDecisionSessionId(activeSessionId);
            response.setDecisionStatus("CLARIFYING");
            response.setAnswer("我还没有拿到你的当前位置。你可以允许浏览器定位后重试，或者直接告诉我城市。");
            recordTurn(chatId, message, response);
            return response;
        }
        if (useDeviceLocationForPoiDisambiguation && suspendedDecision != null) {
            com.hmdp.ai.dto.DecisionConstraints criteria = conversationStateService.activeCriteria(
                    conversationStateService.workingMemory(state));
            if (criteria != null && hasText(criteria.getTargetArea())) {
                // Device location disambiguates the short POI first. Do not
                // resume restaurant search until a canonical POI is selected.
                ChatMessageResponse poiResponse = buildLocationResolutionResponse(
                        chatId, message, state, activeSessionId, suspendedDecision, criteria.getTargetArea());
                if (poiResponse != null) return poiResponse;
                // A short POI with no compatible canonical candidate must remain in
                // clarification. Never silently promote the browser GPS to the
                // final search anchor when entity resolution failed.
                response.setRoute("LOCATION_RESOLUTION");
                response.setDecision(suspendedDecision);
                response.setDecisionSessionId(activeSessionId);
                response.setDecisionStatus("CLARIFYING");
                response.setAnswer("暂时无法根据当前位置确认“" + criteria.getTargetArea()
                        + "”对应的具体地点，请补充完整名称或校区。");
                suspendedDecision.getOptions().clear();
                suspendedDecision.getOptions().add(new com.hmdp.ai.dto.DecisionOption("END_DECISION", "结束本次推荐"));
                recordTurn(chatId, message, response);
                return response;
            }
        }
        DecisionFollowUpRequest followUp = new DecisionFollowUpRequest();
        followUp.setSelectedOptionId(optionId);
        followUp.setMessage(message);
        followUp.setLocationName(confirmedLocationName);
        if ("PROVIDE_LOCATION".equals(optionId)) {
            ConversationLocationSlot location = useDeviceLocationForPoiDisambiguation
                    ? conversationStateService.usableLocation(state)
                    : conversationStateService.usableSearchLocation(state);
            if (location == null) location = conversationStateService.usableLocation(state);
            if (location == null) throw new IllegalArgumentException("当前没有有效位置，请重新授权定位后继续");
            followUp.setLatitude(location.getLatitude());
            followUp.setLongitude(location.getLongitude());
            followUp.setProvince(location.getProvince());
            followUp.setCity(location.getCity());
            followUp.setDistrict(location.getDistrict());
        }
        log.info("[AI][chat] event=DECISION_EVENT chatId={} sessionId={} optionId={}", chatId, activeSessionId, optionId);
        DecisionResponse decision = decisionService.continueDecision(activeSessionId, followUp);
        response.setDecision(decision);
        response.setDecisionSessionId("CANCELLED".equals(decision.getStatus()) ? null : decision.getSessionId());
        response.setDecisionStatus(decision.getStatus());
        response.setAnswer(decision.getAnswer() == null ? decision.getQuestion() : decision.getAnswer());
        if ("CANCELLED".equals(decision.getStatus())) conversationStateService.clearActiveDecision(state);
        else conversationStateService.activateDecision(state, decision.getSessionId());
        com.hmdp.ai.runtime.DecisionCommand relaxationCommand = relaxationCommand(optionId);
        if (suspendedDecision != null && "WAITING_RELAXATION".equals(suspendedDecision.getStatus())
                && relaxationCommand != null && !"CANCELLED".equals(decision.getStatus())) {
            conversationStateService.applyDecisionRelaxationCommand(state, relaxationCommand, decision.getConstraints());
        }
        if ("PROVIDE_LOCATION".equals(optionId) && !confirmedNamedLocation) {
            conversationStateService.applyCurrentDeviceSearchScope(state, decision.getConstraints());
        }
        conversationStateService.snapshotDecision(state, decision);
        recordTurn(chatId, message, response);
        return response;
    }

    private com.hmdp.ai.runtime.DecisionCommand relaxationCommand(String optionId) {
        if (optionId == null) return null;
        try {
            com.hmdp.ai.runtime.DecisionCommand command = com.hmdp.ai.runtime.DecisionCommand.valueOf(optionId);
            switch (command) {
                case BROADEN_FOOD_SCOPE:
                case RELAX_CUISINE:
                case EXPAND_RADIUS:
                case INCREASE_BUDGET:
                case RELAX_QUIET:
                case ALLOW_QUEUE:
                case RELAX_LIGHT_TASTE:
                case RELAX_HARD_CONSTRAINTS:
                    return command;
                default:
                    return null;
            }
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void applyLocationSlot(DecisionRequest request, AiChatSession state,
                                   com.hmdp.ai.dto.DecisionConstraints criteria) {
        com.hmdp.ai.dto.ConversationWorkingMemory memory = conversationStateService.workingMemory(state);
        boolean hasAdministrativeTarget = criteria != null && (hasText(criteria.getTargetProvince())
                || hasText(criteria.getTargetCity()) || hasText(criteria.getTargetDistrict()));
        ConversationLocationSlot taskSearchLocation = conversationStateService.searchLocation(memory);
        boolean unresolvedPoi = criteria != null && hasText(criteria.getTargetArea()) && !hasAdministrativeTarget
                && !hasCoordinates(taskSearchLocation);
        boolean mayUseDeviceLocation = criteria == null
                || (!"EXPLICIT_TARGET".equalsIgnoreCase(criteria.getLocationIntent())
                && !hasText(criteria.getTargetProvince()) && !hasText(criteria.getTargetCity()) && !hasText(criteria.getTargetDistrict()) && !hasText(criteria.getTargetArea()));
        if (criteria != null && (hasText(criteria.getTargetProvince()) || hasText(criteria.getTargetCity()) || hasText(criteria.getTargetDistrict()))) {
            request.setProvince(hasText(criteria.getTargetProvince()) ? criteria.getTargetProvince() : null);
            request.setCity(hasText(criteria.getTargetCity()) ? criteria.getTargetCity() : null);
            request.setDistrict(hasText(criteria.getTargetDistrict()) ? criteria.getTargetDistrict() : null);
            request.setLatitude(null);
            request.setLongitude(null);
            request.setLocationStatus("RESOLVED_BY_NAME");
            request.setUseLocationScope(false);
            log.info("[AI][chat] event=NAMED_SEARCH_SCOPE_APPLIED chatId={} province={} city={} district={} source=ACTIVE_CRITERIA",
                    state.getChatId(), request.getProvince(), request.getCity(), request.getDistrict());
            return;
        }
        if (unresolvedPoi) {
            request.setLocationStatus("MISSING");
            request.setLatitude(null);
            request.setLongitude(null);
            request.setUseLocationScope(false);
            log.info("[AI][chat] event=LOCATION_POI_UNRESOLVED chatId={} targetArea={} source=TURN_SEMANTICS",
                    state.getChatId(), criteria.getTargetArea());
            return;
        }
        ConversationLocationSlot location = conversationStateService.usableSearchLocation(state);
        if (location == null && mayUseDeviceLocation) location = conversationStateService.usableLocation(state);
        if (location != null) {
            request.setLatitude(location.getLatitude());
            request.setLongitude(location.getLongitude());
            request.setLocationName(location.getCanonicalName());
            request.setProvince(location.getProvince());
            request.setCity(location.getCity());
            request.setDistrict(location.getDistrict());
            request.setLocationStatus("AVAILABLE");
            request.setUseLocationScope(true);
            log.info("[AI][chat] event=SLOT_REUSED chatId={} slot=location source={} latitude={} longitude={}",
                    state.getChatId(), location.getSource(), location.getLatitude(), location.getLongitude());
            return;
        }
        ConversationSlots slots = conversationStateService.slots(state);
        ConversationLocationSlot deviceLocation = slots == null ? null : slots.getLocation();
        String status = mayUseDeviceLocation && deviceLocation != null ? deviceLocation.getStatus() : "MISSING";
        request.setLocationStatus(status == null ? "MISSING" : status);
        log.info("[AI][chat] event=SLOT_READ chatId={} slot=location status={}", state.getChatId(), request.getLocationStatus());
    }

    private String cleanRetrievalQuery(String query, com.hmdp.ai.dto.DecisionConstraints constraints) {
        String cleaned = query == null ? "" : query;
        if (hasText(constraints.getTargetCity())) cleaned = cleaned.replace(constraints.getTargetCity(), " ");
        if (hasText(constraints.getTargetDistrict())) cleaned = cleaned.replace(constraints.getTargetDistrict(), " ");
        if (hasText(constraints.getTargetProvince())) cleaned = cleaned.replace(constraints.getTargetProvince(), " ");
        if (hasText(constraints.getTargetArea())) cleaned = cleaned.replace(constraints.getTargetArea(), " ");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return hasText(cleaned) ? cleaned : (hasText(constraints.getKeyword()) ? constraints.getKeyword() : query);
    }

    private Long resolveActiveSessionId(AiChatSession state, Long clientSessionId) {
        if (state.getActiveDecisionSessionId() != null) {
            if (clientSessionId != null && !clientSessionId.equals(state.getActiveDecisionSessionId())) {
                log.warn("[AI][chat] event=CLIENT_SESSION_IGNORED chatId={} clientSessionId={} activeSessionId={}", state.getChatId(),
                        clientSessionId, state.getActiveDecisionSessionId());
            }
            return state.getActiveDecisionSessionId();
        }
        return clientSessionId;
    }

    private Long resolveFollowUpSessionId(String chatId, AiChatSession state, Long activeSessionId) {
        if (activeSessionId != null) return activeSessionId;
        Long sessionId = state.getLastDecisionSessionId();
        boolean restoredFromHistory = false;
        if (sessionId == null) {
            sessionId = chatMemoryService.findLatestDecisionSessionId(chatId);
            restoredFromHistory = sessionId != null && sessionId > 0;
        }
        if (sessionId != null && sessionId <= 0) sessionId = null;
        if (restoredFromHistory) conversationStateService.rememberLastDecision(state, sessionId);
        log.info("[AI][chat] event=FOLLOW_UP_CONTEXT_RESOLVED chatId={} sessionId={} source={}", chatId, sessionId,
                activeSessionId != null ? "ACTIVE" : (state.getLastDecisionSessionId() == null ? "MESSAGE_HISTORY" : "LAST"));
        return sessionId;
    }

    private boolean containsUngroundedRecommendation(String answer) {
        if (answer == null) return false;
        boolean numberedList = answer.matches("(?s).*\\n?\\s*1[.、].*");
        boolean businessMention = answer.contains("餐厅") || answer.contains("餐馆") || answer.contains("饭店");
        return numberedList && businessMention;
    }
}
