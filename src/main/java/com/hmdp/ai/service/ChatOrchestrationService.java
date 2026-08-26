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
import com.hmdp.ai.dto.ConversationLocationSlot;
import com.hmdp.ai.dto.ConversationSlots;
import com.hmdp.ai.dto.AgentSessionContext;
import com.hmdp.ai.dto.ContextRewriteResult;
import com.hmdp.ai.dto.ResolvedLocationCandidate;
import com.hmdp.ai.dto.PolicyDecision;
import com.hmdp.ai.dto.RewriteIntentType;
import com.hmdp.ai.entity.AiChatSession;
import com.hmdp.ai.runtime.ConversationEventStatus;
import com.hmdp.ai.runtime.ConversationEventType;
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
    @Resource private OpenAiCompatibleClient aiClient;
    @Resource private SpringAiTextClient springAiTextClient;
    @Resource private AiProperties aiProperties;
    @Resource private ConsumptionDecisionService decisionService;
    @Resource private AgentConversationService conversationService;
    @Resource private ChatMemoryService chatMemoryService;
    @Resource private ConversationStateService conversationStateService;
    @Resource private AmapMcpLocationResolutionService locationResolutionService;
    @Resource private ConversationContextRewriter contextRewriter;
    @Resource private ConstraintExtractor constraintExtractor;
    @Resource private ConversationCriteriaMerger criteriaMerger;
    @Resource private PolicyDecisionEngine policyDecisionEngine;
    @Resource private ConversationEventService conversationEventService;
    @Resource private ObjectMapper objectMapper;

    public ChatMessageResponse chat(ChatMessageRequest request) {
        return chat(request, null);
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
            conversationEventService.record(ConversationEventType.USER_INPUT, ConversationEventStatus.SUCCESS,
                    null, null, input, null);
        }
        AiChatSession state = conversationStateService.getOrCreate(context.getChatId());
        if (request.getLocation() != null) conversationStateService.acceptLocation(state, request.getLocation());
        context.setChatSession(state);
        context.setWorkingMemory(conversationStateService.workingMemory(state));
        Long activeSessionId = resolveActiveSessionId(state, request.getDecisionSessionId());
        context.setActiveDecisionSessionId(activeSessionId);
        context.setActiveDecision(activeSessionId == null ? null : decisionService.getDecision(activeSessionId));
        log.info("[AI][chat] event=MEMORY_LOADED chatId={} messages={}", context.getChatId(), context.getChatHistory().size());
        log.info("[AI][chat] event=TURN_START chatId={} clientSessionId={} activeSessionId={} status={} query={}",
                context.getChatId(), request.getDecisionSessionId(), activeSessionId,
                context.getActiveDecision() == null ? "NONE" : context.getActiveDecision().getStatus(),
                compact(context.getOriginalMessage()));
    }

    @Override
    public void rewrite(ChatProcessingContext context) {
        context.setContextRewrite(rewriteContext(context.getOriginalMessage(), context.getChatHistory(),
                context.getChatSession(), context.getActiveDecisionSessionId()));
        context.setEffectiveMessage(context.getContextRewrite().getRewrittenQuery());
        if (conversationEventService != null) {
            Map<String, Object> rewrite = new LinkedHashMap<String, Object>();
            rewrite.put("original", context.getOriginalMessage());
            rewrite.put("rewritten", context.getEffectiveMessage());
            rewrite.put("applied", context.getContextRewrite().getApplied());
            rewrite.put("reason", context.getContextRewrite().getReason());
            conversationEventService.record(ConversationEventType.REWRITE, ConversationEventStatus.SUCCESS,
                    null, null, rewrite, null);
        }
    }

    @Override
    public void route(ChatProcessingContext context) {
        ChatMessageRequest request = context.getRequest();
        DecisionResponse activeDecision = context.getActiveDecision();
        String message = context.getOriginalMessage();
        if (request.getSelectedOptionId() != null && isPausedDecision(activeDecision)) {
            context.setAction(com.hmdp.ai.service.pipeline.ChatProcessingAction.DECISION_EVENT);
            context.setRoutingReason("selected_option_for_paused_decision");
            return;
        }
        if (isLocationClarification(activeDecision) && isPotentialNamedLocation(message)) {
            context.setAction(com.hmdp.ai.service.pipeline.ChatProcessingAction.LOCATION_RESOLUTION);
            context.setRoutingReason("named_location_for_clarification");
            ChatMessageResponse locationResponse = resolveNamedLocation(context.getChatId(), message,
                    context.getChatSession(), context.getActiveDecisionSessionId(), activeDecision);
            if (locationResponse != null) {
                context.setResponse(locationResponse);
                return;
            }
        }
        if (isSuspendedDecision(activeDecision) && request.getSelectedOptionId() == null
                && isSuspendedDecisionMetaQuestion(message)) {
            context.setAction(com.hmdp.ai.service.pipeline.ChatProcessingAction.EXPLAIN_SUSPENDED);
            context.setRoutingReason("suspended_decision_meta_question");
            return;
        }
        boolean replacesPausedDecision = isPausedDecision(activeDecision) && request.getSelectedOptionId() == null
                && (context.getContextRewrite().getIntentType() == RewriteIntentType.SEARCH_REFINEMENT
                || isNewRecommendationIntent(context.getEffectiveMessage())
                || isContinuationRefinement(context.getEffectiveMessage(), context.getChatSession()));
        if (replacesPausedDecision) {
            cancelPausedDecision(context);
            context.setAction(com.hmdp.ai.service.pipeline.ChatProcessingAction.START_DECISION);
            context.setRoutingReason("new_recommendation_replaces_paused_decision");
            context.setUsedModel(false);
            return;
        }
        if (isNewRecommendationIntent(message)
                || context.getContextRewrite().getIntentType() == RewriteIntentType.SEARCH_REFINEMENT) {
            context.setAction(com.hmdp.ai.service.pipeline.ChatProcessingAction.START_DECISION);
            context.setRoutingReason("new_recommendation_intent");
            context.setUsedModel(aiProperties.isConfigured());
            return;
        }
        String route = resolveContextualFollowUpRoute(context.getChatId(), context.getEffectiveMessage(), context.getChatSession(),
                context.getActiveDecisionSessionId(), activeDecision, context.getContextRewrite());
        if (route == null) route = route(context.getEffectiveMessage(), activeDecision == null ? "NONE" : activeDecision.getStatus(), context.getChatHistory());
        context.setRoute(route);
        context.setAction(toProcessingAction(route));
        context.setRoutingReason("resolved_route:" + route);
        context.setUsedModel(aiProperties.isConfigured());
        if (conversationEventService != null) {
            conversationEventService.record(ConversationEventType.ROUTE_DECISION, ConversationEventStatus.SUCCESS,
                    null, null, java.util.Collections.<String, Object>singletonMap("route", route), null);
        }
        log.info("[AI][chat] event=ROUTE_SELECTED chatId={} activeSessionId={} route={}",
                context.getChatId(), context.getActiveDecisionSessionId(), route);
    }

    @Override
    public void reduceCriteria(ChatProcessingContext context) {
        if (context.getAction() != com.hmdp.ai.service.pipeline.ChatProcessingAction.START_DECISION) return;
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
        request.setMaxCandidates(3);
        context.setDecisionRequest(request);
        if (context.getWorkingMemory() == null) {
            request.setQuery(context.getEffectiveMessage());
            return;
        }
        List<Long> excludedCandidates = refinementExclusions(context.getContextRewrite(), context.getWorkingMemory());
        request.setExcludeShopIds(excludedCandidates);
        com.hmdp.ai.dto.CriteriaMergeResult mergeResult = criteriaMerger.merge(
                context.getWorkingMemory().getActiveCriteria(), constraintExtractor.extract(context.getEffectiveMessage()),
                context.getOriginalMessage(), context.getWorkingMemory().getCandidatePool(), context.getWorkingMemory().getFocusedShopId());
        context.setCriteriaMergeResult(mergeResult);
        context.setMergedConstraints(mergeResult.getConstraints());
        request.setQuery(cleanRetrievalQuery(context.getEffectiveMessage(), mergeResult.getConstraints()));
        conversationStateService.reduceCriteria(context.getChatSession(), mergeResult);
        conversationStateService.applyNamedSearchLocation(context.getChatSession(), mergeResult.getConstraints());
        applyLocationSlot(request, context.getChatSession(), mergeResult.getConstraints());
        log.info("[AI][chat] event=CRITERIA_MERGED chatId={} inherited={} replaced={} appended={} cleared={} invalidated={} query={}",
                context.getChatId(), mergeResult.getInherited(), mergeResult.getReplaced(), mergeResult.getAppended(),
                mergeResult.getCleared(), mergeResult.getInvalidated(), compact(context.getEffectiveMessage()));
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
                        context.getActiveDecisionSessionId(), context.getActiveDecision());
            case START_DECISION:
                return executeDecision(context);
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
            conversationEventService.record(ConversationEventType.DECISION_STARTED, ConversationEventStatus.SUCCESS,
                    null, null, result, null);
        }
        conversationStateService.activateDecision(context.getChatSession(), decision.getSessionId());
        conversationStateService.snapshotDecision(context.getChatSession(), decision);
        return buildDecisionResponse(context.getChatId(), context.getOriginalMessage(), context.getChatSession(), context.isUsedModel(),
                context.getContextRewrite(), decision, context.getPolicyDecision());
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
        if ("BUSINESS_FOLLOW_UP".equals(route)) return com.hmdp.ai.service.pipeline.ChatProcessingAction.BUSINESS_FOLLOW_UP;
        if ("EXIT_DECISION".equals(route)) return com.hmdp.ai.service.pipeline.ChatProcessingAction.EXIT_DECISION;
        if ("EXPLAIN_SUSPENDED_DECISION".equals(route)) return com.hmdp.ai.service.pipeline.ChatProcessingAction.EXPLAIN_SUSPENDED;
        return com.hmdp.ai.service.pipeline.ChatProcessingAction.GENERAL_CHAT;
    }

    private ChatMessageResponse executeLegacy(ChatMessageRequest request, Consumer<String> textDeltaConsumer) {
        if (request == null || request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        String message = request.getMessage().trim();
        String chatId = chatMemoryService.resolveChatId(request.getChatId());
        List<Map<String, Object>> chatHistory = chatMemoryService.load(chatId);
        if (conversationEventService != null) {
            conversationEventService.begin(chatId, chatHistory.size() / 2 + 1);
            Map<String, Object> input = new LinkedHashMap<String, Object>();
            input.put("content", message);
            input.put("decisionSessionId", request.getDecisionSessionId());
            conversationEventService.record(ConversationEventType.USER_INPUT, ConversationEventStatus.SUCCESS,
                    null, null, input, null);
        }
        AiChatSession state = conversationStateService.getOrCreate(chatId);
        if (request.getLocation() != null) conversationStateService.acceptLocation(state, request.getLocation());
        Long activeSessionId = resolveActiveSessionId(state, request.getDecisionSessionId());
        DecisionResponse activeDecision = activeSessionId == null ? null : decisionService.getDecision(activeSessionId);
        log.info("[AI][chat] event=MEMORY_LOADED chatId={} messages={}", chatId, chatHistory.size());
        log.info("[AI][chat] event=TURN_START chatId={} clientSessionId={} activeSessionId={} status={} query={}", chatId,
                request.getDecisionSessionId(), activeSessionId,
                activeDecision == null ? "NONE" : activeDecision.getStatus(), compact(message));
        if (request.getSelectedOptionId() != null && isPausedDecision(activeDecision)) {
            ChatMessageResponse eventResponse = new ChatMessageResponse();
            eventResponse.setChatId(chatId);
            eventResponse.setRoute("DECISION_EVENT");
            eventResponse.setUsedModel(false);
            return handleDecisionEvent(chatId, message, request, state, activeSessionId, eventResponse);
        }
        if (isLocationClarification(activeDecision) && isPotentialNamedLocation(message)) {
            ChatMessageResponse locationResponse = resolveNamedLocation(chatId, message, state, activeSessionId, activeDecision);
            if (locationResponse != null) return locationResponse;
        }
        if (isSuspendedDecision(activeDecision) && request.getSelectedOptionId() == null
                && isSuspendedDecisionMetaQuestion(message)) {
            return explainSuspendedDecision(chatId, message, activeSessionId, activeDecision);
        }
        ContextRewriteResult contextRewrite = rewriteContext(message, chatHistory, state, activeSessionId);
        String effectiveMessage = contextRewrite.getRewrittenQuery();
        if (conversationEventService != null) {
            Map<String, Object> rewrite = new LinkedHashMap<String, Object>();
            rewrite.put("original", message); rewrite.put("rewritten", effectiveMessage);
            rewrite.put("applied", contextRewrite.getApplied()); rewrite.put("reason", contextRewrite.getReason());
            conversationEventService.record(ConversationEventType.REWRITE, ConversationEventStatus.SUCCESS,
                    null, null, rewrite, null);
        }
        if (isPausedDecision(activeDecision) && request.getSelectedOptionId() == null
                && (contextRewrite.getIntentType() == RewriteIntentType.SEARCH_REFINEMENT
                || isNewRecommendationIntent(effectiveMessage) || isContinuationRefinement(effectiveMessage, state))) {
            DecisionFollowUpRequest cancel = new DecisionFollowUpRequest();
            cancel.setSelectedOptionId("END_DECISION");
            cancel.setMessage("被新的餐饮需求替代：" + effectiveMessage);
            decisionService.continueDecision(activeSessionId, cancel);
            conversationStateService.clearActiveDecision(state);
            log.info("[AI][chat] event=PENDING_DECISION_SUPERSEDED chatId={} previousSessionId={} query={}",
                    chatId, activeSessionId, compact(effectiveMessage));
            log.info("[AI][chat] event=ROUTE_SELECTED chatId={} activeSessionId={} route=START_DECISION source=PENDING_SUPERSEDE",
                    chatId, activeSessionId);
            return startDecision(chatId, message, effectiveMessage, state, request.getLocation() != null, false, contextRewrite);
        }
        // A new search must take precedence over candidate references from the previous result.
        // Otherwise "重新推荐福州小吃" can be incorrectly treated as a follow-up to a Hangzhou shop.
        if (isNewRecommendationIntent(message)) {
            log.info("[AI][chat] event=ROUTE_GUARD_MATCHED chatId={} activeSessionId={} route=START_DECISION source=NEW_RECOMMENDATION query={}",
                    chatId, activeSessionId, compact(message));
            return startDecision(chatId, message, effectiveMessage, state, request.getLocation() != null, aiProperties.isConfigured(), contextRewrite);
        }
        if (contextRewrite.getIntentType() == RewriteIntentType.SEARCH_REFINEMENT) {
            log.info("[AI][chat] event=ROUTE_GUARD_MATCHED chatId={} activeSessionId={} route=START_DECISION source=CONTEXT_REWRITE_REFINEMENT query={}",
                    chatId, activeSessionId, compact(effectiveMessage));
            return startDecision(chatId, message, effectiveMessage, state, request.getLocation() != null, aiProperties.isConfigured(), contextRewrite);
        }
        String route = resolveContextualFollowUpRoute(chatId, effectiveMessage, state, activeSessionId, activeDecision,
                contextRewrite);
        if (route == null) route = route(effectiveMessage, activeDecision == null ? "NONE" : activeDecision.getStatus(), chatHistory);
        if (conversationEventService != null) {
            conversationEventService.record(ConversationEventType.ROUTE_DECISION, ConversationEventStatus.SUCCESS,
                    null, null, java.util.Collections.<String, Object>singletonMap("route", route), null);
        }
        log.info("[AI][chat] event=ROUTE_SELECTED chatId={} activeSessionId={} route={}", chatId, activeSessionId, route);
        ChatMessageResponse response = new ChatMessageResponse();
        response.setChatId(chatId);
        response.setRoute(route);
        response.setUsedModel(aiProperties.isConfigured());
        response.setContextRewrite(contextRewrite);
        if ("EXPLAIN_SUSPENDED_DECISION".equals(route) && isSuspendedDecision(activeDecision)) {
            return explainSuspendedDecision(chatId, message, activeSessionId, activeDecision);
        }
        if ("START_DECISION".equals(route)) {
            return startDecision(chatId, message, effectiveMessage, state, request.getLocation() != null, aiProperties.isConfigured(), contextRewrite);
        }
        if ("BUSINESS_FOLLOW_UP".equals(route)) {
            Long followUpSessionId = resolveFollowUpSessionId(chatId, state, activeSessionId);
            if (followUpSessionId == null) {
                response.setAnswer("我没有找到可以关联的推荐会话。请告诉我店名，或者重新说一下你的用餐需求，我会先查询再回答。");
                recordTurn(chatId, message, response);
                return response;
            }
            DecisionResponse followUpDecision = decisionService.getDecision(followUpSessionId);
            if (!"COMPLETED".equals(followUpDecision.getStatus())) {
                response.setDecisionSessionId(followUpSessionId);
                response.setDecisionStatus(followUpDecision.getStatus());
                response.setAnswer("刚才的推荐还在等待补充条件，完成推荐后我才能查询具体商户的评价、优惠券或备选。");
                recordTurn(chatId, message, response);
                return response;
            }
            AgentConversationRequest followUp = new AgentConversationRequest();
            followUp.setMessage(effectiveMessage);
            PolicyDecision policy = policyDecisionEngine == null ? null : policyDecisionEngine.decideFollowUp(effectiveMessage);
            recordPolicy(state, chatId, followUpSessionId, policy);
            applyPolicy(response, policy);
            AgentSessionContext followUpContext = conversationStateService.agentContext(state);
            response.setConversation(conversationService.converse(followUpSessionId, followUp, followUpContext));
            conversationStateService.applyAgentContext(state, followUpSessionId, followUpContext);
            response.setDecisionSessionId(followUpSessionId);
            response.setDecisionStatus(followUpDecision.getStatus());
            response.setAnswer(response.getConversation().getAnswer());
            recordTurn(chatId, message, response);
            return response;
        }
        if ("EXIT_DECISION".equals(route) && activeDecision != null) {
            if (isPausedDecision(activeDecision)) {
                DecisionFollowUpRequest followUp = new DecisionFollowUpRequest();
                followUp.setMessage(message);
                decisionService.continueDecision(activeSessionId, followUp);
            }
            conversationStateService.clearActiveDecision(state);
            response.setDecisionSessionId(null);
        }
        if ("GENERAL_CHAT".equals(route) && activeDecision != null) {
            response.setDecisionSessionId(activeSessionId);
            response.setDecisionStatus(activeDecision.getStatus());
        }
        response.setAnswer(generalReply(message, chatHistory, conversationStateService.workingMemory(state), textDeltaConsumer));
        if (!aiProperties.isConfigured()) {
            response.setUsedModel(false);
            response.setDegradedReason("模型服务未配置：当前后端进程没有读取到 DEEPSEEK_API_KEY，本次使用本地对话降级回复。");
            log.warn("[AI][chat] action=GENERAL_CHAT event=MODEL_NOT_CONFIGURED");
        }
        recordTurn(chatId, message, response);
        return response;
    }

    private ChatMessageResponse startDecision(String chatId, String originalMessage, String effectiveMessage,
                                              AiChatSession state, boolean submittedDeviceLocation, boolean usedModel,
                                              ContextRewriteResult contextRewrite) {
        DecisionRequest decisionRequest = new DecisionRequest();
        decisionRequest.setMaxCandidates(3);
        com.hmdp.ai.dto.ConversationWorkingMemory memory = conversationStateService.workingMemory(state);
        if (memory == null) {
            // Keeps isolated gateway tests compatible with the pre-working-memory state mock.
            decisionRequest.setQuery(effectiveMessage);
            DecisionResponse decision = decisionService.decide(decisionRequest);
            conversationStateService.activateDecision(state, decision.getSessionId());
            return buildDecisionResponse(chatId, originalMessage, state, usedModel, contextRewrite, decision, null);
        }
        List<Long> excludedCandidates = refinementExclusions(contextRewrite, memory);
        decisionRequest.setExcludeShopIds(excludedCandidates);
        com.hmdp.ai.dto.CriteriaMergeResult mergeResult = criteriaMerger.merge(memory.getActiveCriteria(),
                constraintExtractor.extract(effectiveMessage), originalMessage, memory.getCandidatePool(), memory.getFocusedShopId());
        decisionRequest.setQuery(cleanRetrievalQuery(effectiveMessage, mergeResult.getConstraints()));
        conversationStateService.reduceCriteria(state, mergeResult);
        conversationStateService.applyNamedSearchLocation(state, mergeResult.getConstraints());
        String explicitLocationScope = locationResolutionScope(mergeResult.getConstraints());
        applyLocationSlot(decisionRequest, state, mergeResult.getConstraints());
        log.info("[AI][chat] event=CRITERIA_MERGED chatId={} inherited={} replaced={} appended={} cleared={} invalidated={} query={}", chatId,
                mergeResult.getInherited(), mergeResult.getReplaced(), mergeResult.getAppended(), mergeResult.getCleared(),
                mergeResult.getInvalidated(), compact(effectiveMessage));
        PolicyDecision policy = policyDecisionEngine == null ? null : policyDecisionEngine.decideRecommendation(
                decisionRequest, mergeResult.getConstraints(), memory);
        recordPolicy(state, chatId, null, policy);
        DecisionResponse decision = decisionService.decide(decisionRequest, mergeResult.getConstraints(), chatId,
                conversationEventService == null || conversationEventService.currentTrace() == null ? null
                        : conversationEventService.currentTrace().getTraceId());
        if (conversationEventService != null) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("decisionSessionId", decision.getSessionId()); result.put("status", decision.getStatus());
            conversationEventService.record(ConversationEventType.DECISION_STARTED, ConversationEventStatus.SUCCESS,
                    null, null, result, null);
        }
        conversationStateService.activateDecision(state, decision.getSessionId());
        conversationStateService.snapshotDecision(state, decision);
        return buildDecisionResponse(chatId, originalMessage, state, usedModel, contextRewrite, decision, policy);
    }

    private List<Long> refinementExclusions(ContextRewriteResult contextRewrite,
                                            com.hmdp.ai.dto.ConversationWorkingMemory memory) {
        List<Long> excluded = new ArrayList<Long>();
        if (contextRewrite == null || contextRewrite.getIntentType() != RewriteIntentType.SEARCH_REFINEMENT
                || memory == null || memory.getCandidatePool() == null) return excluded;
        for (com.hmdp.ai.dto.DecisionRecommendation candidate : memory.getCandidatePool()) {
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
            messages.add(message("system", "你是消费决策 Agent 的对话路由器。当前业务只支持餐饮商户的消费决策。根据用户最新一句话选择路由：GENERAL_CHAT=普通闲聊、能力问答、非餐饮需求或需求不完整；START_DECISION=用户明确要找餐厅、吃饭、菜品、订餐或餐饮消费推荐；BUSINESS_FOLLOW_UP=围绕已推荐餐饮商户问优惠券、评价、备选或比较；EXIT_DECISION=用户明确说算了、不找了、结束或不需要了。‘附近有啥’、‘有什么推荐’这类未说明餐饮意图的句子必须是 GENERAL_CHAT，先自然追问想找什么，不能擅自开始餐饮检索。游泳、健身、运动场馆、医院、景点、住宿、交通等即使包含‘附近’也必须是 GENERAL_CHAT，绝不能进入餐饮推荐。"));
            messages.add(message("system", "当前决策状态=" + decisionStatus));
            messages.add(message("system", "优先规则：若前序对话已处于餐饮推荐上下文，或状态为 ZERO_RESULT_NO_DATA、WAITING_RELAXATION，用户说“我附近”“当前位置”“当前定位”“换个地方”或“其他商圈”时，必须继承餐饮推荐意图并选择 START_DECISION；不要把它当作 GENERAL_CHAT。"));
            messages.add(message("system", "若当前状态为 WAITING_RELAXATION 或 ZERO_RESULT_NO_DATA，用户是在追问暂停原因、已确认地点、可放宽项或下一步如何处理时，选择 EXPLAIN_SUSPENDED_DECISION；该路由不发起新搜索，也不查询商户详情。"));
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
        if (memory == null || memory.getActiveCriteria() == null) return "尚未确认搜索条件。";
        com.hmdp.ai.dto.DecisionConstraints criteria = memory.getActiveCriteria();
        List<String> facts = new ArrayList<>();
        if (hasText(criteria.getTargetCity())) facts.add("已锁定目标城市=" + criteria.getTargetCity());
        if (hasText(criteria.getTargetArea())) facts.add("已锁定目标区域=" + criteria.getTargetArea());
        if (hasText(criteria.getCuisine())) facts.add("菜系=" + criteria.getCuisine());
        if (criteria.getBudgetPerPerson() > 0) facts.add("预算上限=" + criteria.getBudgetPerPerson());
        if (memory.getCandidatePool() != null && !memory.getCandidatePool().isEmpty()) {
            facts.add("当前候选店铺数=" + memory.getCandidatePool().size());
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
        if (contextRewrite.getIntentType() == RewriteIntentType.SHOP_INQUIRY) {
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

    private boolean isNewRecommendationIntent(String message) {
        if (message == null || message.isEmpty() || isFocusedShopQuestion(message)) return false;
        boolean asksForPlace = message.contains("店") || message.contains("餐厅") || message.contains("餐馆")
                || message.contains("地方") || message.contains("吃饭");
        boolean asksForNewOptions = message.contains("有没有") || message.contains("有没") || message.contains("还有")
                || message.contains("推荐") || message.contains("找") || message.contains("来一家");
        boolean hasScene = message.contains("适合约会") || message.contains("约会") || message.contains("聚餐")
                || message.contains("安静") || message.contains("清淡") || message.contains("性价比");
        boolean hasDiningCategory = message.contains("烤肉") || message.contains("烧烤") || message.contains("火锅")
                || message.contains("日料") || message.contains("料理") || message.contains("小吃") || message.contains("咖啡")
                || message.contains("奶茶") || message.contains("川菜") || message.contains("粤菜")
                || message.contains("简餐") || message.contains("快餐");
        boolean refinement = message.contains("换成") || message.contains("改成") || message.contains("便宜点")
                || message.contains("贵点") || message.contains("不要辣") || message.contains("不吃辣");
        boolean explicitSearchVerb = message.contains("帮我找") || message.contains("帮我搜") || message.contains("找一下")
                || message.contains("推荐一下") || message.contains("给我推荐");
        boolean mealPlanChanged = message.contains("取消") || message.contains("改吃") || message.contains("我自己")
                || message.contains("一个人吃") || message.contains("单人");
        boolean locationSearchContinuation = message.contains("附近")
                && (message.contains("找") || message.contains("推荐") || message.contains("看看"));
        return (asksForPlace && (asksForNewOptions || hasScene || hasDiningCategory))
                || (hasDiningCategory && (explicitSearchVerb || mealPlanChanged)) || (refinement && hasDiningCategory)
                || locationSearchContinuation;
    }

    private boolean isFocusedShopQuestion(String message) {
        return message.contains("这家") || message.contains("那家") || message.contains("这一个")
                || message.contains("上一家") || message.contains("刚才那家");
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
        String[] metaTerms = {"放宽", "什么条件", "什么意思", "怎么回事", "为什么", "刚刚", "不是说", "不是已经", "什么东西"};
        for (String term : metaTerms) if (normalized.contains(term)) return true;
        return false;
    }

    private ChatMessageResponse explainSuspendedDecision(String chatId, String message, Long activeSessionId,
                                                         DecisionResponse decision) {
        ChatMessageResponse response = new ChatMessageResponse();
        response.setChatId(chatId);
        response.setRoute("EXPLAIN_SUSPENDED_DECISION");
        response.setUsedModel(false);
        response.setDecision(decision);
        response.setDecisionSessionId(activeSessionId);
        response.setDecisionStatus(decision.getStatus());
        com.hmdp.ai.dto.DecisionConstraints constraints = decision.getConstraints();
        String city = constraints == null ? "" : constraints.getTargetCity();
        String area = constraints == null ? "" : constraints.getTargetArea();
        String scope = hasText(area) ? area : city;
        if ("ZERO_RESULT_NO_DATA".equals(decision.getStatus())) {
            response.setAnswer("我记得你要找" + (hasText(scope) ? scope : "指定城市")
                    + "的餐饮商户。当前暂停不是因为条件需要放宽，而是该范围暂无入库商户；可以切换城市或周边区域后再搜。");
        } else {
            List<String> choices = new ArrayList<>();
            for (com.hmdp.ai.dto.DecisionOption option : decision.getOptions()) {
                if (!"END_DECISION".equals(option.getId())) choices.add(option.getLabel());
            }
            response.setAnswer(choices.isEmpty() ? "当前没有可放宽的条件，推荐已暂停。"
                    : "当前没有匹配商户，可以选择放宽以下任一条件后继续：" + String.join("；", choices) + "。");
        }
        log.info("[AI][chat] event=SUSPENDED_DECISION_EXPLAINED chatId={} sessionId={} status={} query={}",
                chatId, activeSessionId, decision.getStatus(), compact(message));
        recordTurn(chatId, message, response);
        return response;
    }

    /** A paused recommendation may be refined after unrelated small talk. */
    private boolean isContinuationRefinement(String message, AiChatSession state) {
        if (message == null || !message.contains("继续")) return false;
        com.hmdp.ai.dto.DecisionConstraints criteria = conversationStateService.workingMemory(state).getActiveCriteria();
        if (criteria == null) return false;
        boolean hasActiveDemand = hasText(criteria.getCuisine()) || hasText(criteria.getKeyword())
                || hasText(criteria.getTargetCity()) || Boolean.TRUE.equals(criteria.getNearby());
        boolean hasRefinement = message.contains("安静") || message.contains("便宜") || message.contains("更近")
                || message.contains("换") || message.contains("清淡") || message.contains("推荐") || message.contains("找");
        return hasActiveDemand && hasRefinement;
    }

    private boolean isLocationClarification(DecisionResponse decision) {
        return decision != null && "CLARIFYING".equals(decision.getStatus());
    }

    private boolean isPotentialNamedLocation(String message) {
        if (!locationResolutionService.isAvailable()) return false;
        String normalized = message == null ? "" : message.replaceAll("\\s+", "").trim();
        if (normalized.length() < 2 || normalized.length() > 24 || normalized.contains("?") || normalized.contains("？")) return false;
        String[] nonLocationWords = {"这家", "那家", "第一家", "第二家", "评价", "优惠", "代金券", "团购", "营业", "几点",
                "怎么样", "对比", "推荐", "找", "吃", "餐", "店", "火锅", "烧烤", "日料", "小吃", "算了", "结束", "不找了"};
        for (String word : nonLocationWords) {
            if (normalized.contains(word)) return false;
        }
        return normalized.matches("^(?:我在|去|到)?[\\p{IsHan}]{2,16}(?:省|市|区|县|镇|乡|街道|大学城|商圈)?$");
    }

    private ChatMessageResponse resolveNamedLocation(String chatId, String message, AiChatSession state,
                                                     Long activeSessionId, DecisionResponse activeDecision) {
        return buildLocationResolutionResponse(chatId, message, state, activeSessionId, activeDecision, message);
    }

    private ChatMessageResponse buildLocationResolutionResponse(String chatId, String message, AiChatSession state,
                                                                Long activeSessionId, DecisionResponse activeDecision,
                                                                String locationQuery) {
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
        List<ResolvedLocationCandidate> candidates = locationResolutionService.resolve(normalizedLocationQuery);
        if (candidates.isEmpty()) {
            log.info("[AI][chat] event=LOCATION_RESOLUTION_EMPTY chatId={} sessionId={} query={}",
                    chatId, activeSessionId, compact(normalizedLocationQuery));
            return null;
        }
        conversationStateService.rememberLocationCandidates(state, candidates);
        DecisionResponse decision = activeDecision;
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
                chatId, activeSessionId, compact(normalizedLocationQuery), candidates.size());
        recordTurn(chatId, message, response);
        return response;
    }

    private boolean locationServiceAvailable() {
        return locationResolutionService != null && locationResolutionService.isAvailable();
    }

    /** Only an area without a resolved city needs map disambiguation. */
    private String locationResolutionScope(com.hmdp.ai.dto.DecisionConstraints constraints) {
        if (constraints == null || hasText(constraints.getTargetCity())) return null;
        return hasText(constraints.getTargetArea()) ? constraints.getTargetArea() : null;
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
            conversationEventService.record(ConversationEventType.POLICY_DECISION, ConversationEventStatus.SUCCESS,
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

    private boolean isRestaurantSearch(String message) {
        String[] keywords = {"吃", "餐厅", "餐馆", "饭店", "饭", "菜", "烧烤", "烤肉", "火锅", "日料", "料理", "小吃", "咖啡", "奶茶"};
        for (String keyword : keywords) {
            if (message.contains(keyword)) return true;
        }
        return false;
    }

    private Map<String, Object> routeTool() {
        Map<String, Object> route = new LinkedHashMap<String, Object>();
        route.put("type", "string");
        route.put("enum", Arrays.asList("GENERAL_CHAT", "START_DECISION", "BUSINESS_FOLLOW_UP", "EXIT_DECISION", "EXPLAIN_SUSPENDED_DECISION"));
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

    private void recordTurn(String chatId, String userMessage, ChatMessageResponse response) {
        chatMemoryService.appendTurn(chatId, userMessage, response.getAnswer(), response.getRoute(), response.getDecisionSessionId());
        if (conversationEventService != null) {
            Map<String, Object> output = new LinkedHashMap<String, Object>();
            output.put("content", response.getAnswer()); output.put("route", response.getRoute());
            output.put("decisionSessionId", response.getDecisionSessionId());
            conversationEventService.record(ConversationEventType.ASSISTANT_OUTPUT, ConversationEventStatus.SUCCESS,
                    null, null, output, null);
            conversationEventService.clearTrace();
        }
        log.info("[AI][chat] event=MEMORY_SAVED chatId={} userChars={} assistantChars={}", chatId,
                userMessage.length(), response.getAnswer() == null ? 0 : response.getAnswer().length());
    }

    private ChatMessageResponse handleDecisionEvent(String chatId, String message, ChatMessageRequest request,
                                                    AiChatSession state, Long activeSessionId,
                                                    ChatMessageResponse response) {
        String optionId = request.getSelectedOptionId();
        if ("SWITCH_CITY".equals(optionId)) {
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
            int index = Integer.parseInt(optionId.substring("CONFIRM_RESOLVED_LOCATION_".length()));
            ResolvedLocationCandidate candidate = conversationStateService.acceptPendingSearchLocation(state, index);
            optionId = "PROVIDE_LOCATION";
            log.info("[AI][chat] event=LOCATION_RESOLUTION_CONFIRMED chatId={} sessionId={} label={} latitude={} longitude={}",
                    chatId, activeSessionId, candidate.getLabel(), candidate.getLatitude(), candidate.getLongitude());
        }
        if ("DECLINE_LOCATION".equals(optionId)) conversationStateService.declineLocation(state);
        DecisionFollowUpRequest followUp = new DecisionFollowUpRequest();
        followUp.setSelectedOptionId(optionId);
        followUp.setMessage(message);
        if ("PROVIDE_LOCATION".equals(optionId)) {
            ConversationLocationSlot location = conversationStateService.usableSearchLocation(state);
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
        conversationStateService.snapshotDecision(state, decision);
        recordTurn(chatId, message, response);
        return response;
    }

    private void applyLocationSlot(DecisionRequest request, AiChatSession state,
                                   com.hmdp.ai.dto.DecisionConstraints criteria) {
        com.hmdp.ai.dto.ConversationWorkingMemory memory = conversationStateService.workingMemory(state);
        boolean mayUseDeviceLocation = criteria == null
                || (!"EXPLICIT_TARGET".equalsIgnoreCase(criteria.getLocationIntent())
                && !hasText(criteria.getTargetCity()) && !hasText(criteria.getTargetArea()));
        if (criteria != null && hasText(criteria.getTargetCity())) {
            request.setCity(criteria.getTargetCity());
            // Keep an unresolved area as a semantic preference, not a strict SQL filter.
            request.setDistrict(null);
            request.setLatitude(null);
            request.setLongitude(null);
            request.setLocationStatus("RESOLVED_BY_NAME");
            request.setUseLocationScope(true);
            log.info("[AI][chat] event=NAMED_SEARCH_SCOPE_APPLIED chatId={} city={} area={} source=ACTIVE_CRITERIA",
                    state.getChatId(), request.getCity(), request.getDistrict());
            return;
        }
        ConversationLocationSlot location = conversationStateService.usableSearchLocation(state);
        if (location == null && mayUseDeviceLocation) location = conversationStateService.usableLocation(state);
        if (location != null) {
            request.setLatitude(location.getLatitude());
            request.setLongitude(location.getLongitude());
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
