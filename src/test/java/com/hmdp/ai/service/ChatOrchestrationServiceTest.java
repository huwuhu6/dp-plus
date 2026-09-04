package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.client.QueryRewriteClient;
import com.hmdp.ai.client.SpringAiTextClient;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.AgentConversationResponse;
import com.hmdp.ai.dto.ChatLocationInput;
import com.hmdp.ai.dto.ChatMessageRequest;
import com.hmdp.ai.dto.ChatMessageResponse;
import com.hmdp.ai.dto.ContextRewriteResult;
import com.hmdp.ai.dto.ConversationLocationSlot;
import com.hmdp.ai.dto.ConversationSlots;
import com.hmdp.ai.dto.DecisionFollowUpRequest;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.dto.DecisionRequest;
import com.hmdp.ai.dto.ResolvedLocationCandidate;
import com.hmdp.ai.entity.AiChatSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class ChatOrchestrationServiceTest {
    @Test
    void stopsBeforeStateMutationWhenDurableUserInputCannotBeWritten() {
        ChatOrchestrationService service = new ChatOrchestrationService();
        ChatMemoryService memory = mock(ChatMemoryService.class);
        ConversationStateService state = mock(ConversationStateService.class);
        ConversationEventService events = mock(ConversationEventService.class);
        ReflectionTestUtils.setField(service, "chatMemoryService", memory);
        ReflectionTestUtils.setField(service, "conversationStateService", state);
        ReflectionTestUtils.setField(service, "conversationEventService", events);
        when(memory.resolveChatId(any())).thenReturn("chat-durable");
        when(memory.load("chat-durable")).thenReturn(Collections.emptyList());
        doThrow(new IllegalStateException("event store unavailable")).when(events).persistDurableEvent(
                org.mockito.ArgumentMatchers.eq(com.hmdp.ai.runtime.ConversationEventType.USER_INPUT),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("帮我找附近火锅");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.bootstrap(new com.hmdp.ai.service.pipeline.ChatProcessingContext(request, null)));

        verifyNoInteractions(state);
    }

    @Test
    void marksAlternativeRecommendationAsDeterministicRouting() {
        ChatOrchestrationService service = new ChatOrchestrationService();
        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("\u6362\u4e00\u5bb6");
        com.hmdp.ai.service.pipeline.ChatProcessingContext context =
                new com.hmdp.ai.service.pipeline.ChatProcessingContext(request, null);
        context.setOriginalMessage(request.getMessage());
        context.setEffectiveMessage(request.getMessage());

        com.hmdp.ai.runtime.RoutingDecisionAssessment assessment = ReflectionTestUtils.invokeMethod(
                service, "assessRouting", context, false);

        assertEquals(com.hmdp.ai.service.pipeline.ChatProcessingAction.START_DECISION, assessment.getCandidateAction());
        assertEquals("RULE", assessment.getSource());
        assertFalse(assessment.isShouldEscalate());
    }

    @Test
    void escalatesMixedReferenceAndAlternativeRequest() {
        ChatOrchestrationService service = new ChatOrchestrationService();
        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("\u7b2c\u4e00\u5bb6\u600e\u4e48\u6837\uff0c\u6362\u4e00\u5bb6");
        com.hmdp.ai.service.pipeline.ChatProcessingContext context =
                new com.hmdp.ai.service.pipeline.ChatProcessingContext(request, null);
        context.setOriginalMessage(request.getMessage());
        context.setEffectiveMessage(request.getMessage());

        com.hmdp.ai.runtime.RoutingDecisionAssessment assessment = ReflectionTestUtils.invokeMethod(
                service, "assessRouting", context, false);

        assertTrue(assessment.isConflictDetected());
        assertTrue(assessment.isShouldEscalate());
        assertEquals("competing_actions", assessment.getReason());
    }

    @Test
    void marksShopReferenceAsContextRequiredBeforeRewrite() {
        ChatOrchestrationService service = new ChatOrchestrationService();
        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("\u7b2c\u4e00\u5bb6\u6709\u4f18\u60e0\u5238\u5417");
        com.hmdp.ai.service.pipeline.ChatProcessingContext context =
                new com.hmdp.ai.service.pipeline.ChatProcessingContext(request, null);
        context.setOriginalMessage(request.getMessage());
        context.setEffectiveMessage(request.getMessage());

        com.hmdp.ai.runtime.RoutingDecisionAssessment assessment = ReflectionTestUtils.invokeMethod(
                service, "assessRouting", context, false);

        assertEquals(com.hmdp.ai.service.pipeline.ChatProcessingAction.BUSINESS_FOLLOW_UP, assessment.getCandidateAction());
        assertTrue(assessment.isContextRequired());
        assertTrue(assessment.isRequiredContextMissing());
    }

    @Test
    void streamsSafeGeneralChatFromModelAndRetainsCompleteAnswer() throws Exception {
        ChatOrchestrationService service = new ChatOrchestrationService();
        OpenAiCompatibleClient aiClient = mock(OpenAiCompatibleClient.class);
        SpringAiTextClient textClient = mock(SpringAiTextClient.class);
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        ConversationStateService stateService = mock(ConversationStateService.class);
        AiProperties properties = new AiProperties();
        properties.setApiKey("test-key");
        ReflectionTestUtils.setField(service, "aiClient", aiClient);
        ReflectionTestUtils.setField(service, "springAiTextClient", textClient);
        ReflectionTestUtils.setField(service, "aiProperties", properties);
        ReflectionTestUtils.setField(service, "decisionService", mock(ConsumptionDecisionService.class));
        ReflectionTestUtils.setField(service, "conversationService", mock(AgentConversationService.class));
        ReflectionTestUtils.setField(service, "chatMemoryService", memoryService);
        ReflectionTestUtils.setField(service, "conversationStateService", stateService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        when(memoryService.resolveChatId(any())).thenReturn("test-chat");
        when(memoryService.load("test-chat")).thenReturn(Collections.emptyList());
        when(stateService.getOrCreate("test-chat")).thenReturn(new AiChatSession());
        when(aiClient.chatCompletion(any(), any(), any(), any())).thenReturn(new ObjectMapper().readTree(
                "{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"route\\\":\\\"GENERAL_CHAT\\\"}\"}}]}}]}"));
        org.mockito.Mockito.doAnswer(invocation -> {
            Consumer<String> callback = invocation.getArgument(2);
            callback.accept("你好，");
            callback.accept("我是餐饮消费决策助手。");
            return "你好，我是餐饮消费决策助手。";
        }).when(textClient).streamText(any(), org.mockito.Mockito.eq("GENERAL_CHAT"), any());

        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("你是？");
        List<String> deltas = new ArrayList<String>();
        ChatMessageResponse response = service.chat(request, deltas::add);

        assertEquals(Arrays.asList("你好，", "我是餐饮消费决策助手。"), deltas);
        assertEquals("你好，我是餐饮消费决策助手。", response.getAnswer());
        verify(memoryService).appendTurn(org.mockito.Mockito.eq("test-chat"), org.mockito.Mockito.eq("你是？"),
                org.mockito.Mockito.eq(response.getAnswer()), any(), any());
    }

    @Test
    void usesRewrittenQueryDownstreamButPersistsOriginalUserMessage() {
        ChatOrchestrationService service = new ChatOrchestrationService();
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        AgentConversationService conversationService = mock(AgentConversationService.class);
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        ConversationStateService stateService = mock(ConversationStateService.class);
        ConversationContextRewriter rewriter = mock(ConversationContextRewriter.class);
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        ReflectionTestUtils.setField(service, "conversationService", conversationService);
        ReflectionTestUtils.setField(service, "chatMemoryService", memoryService);
        ReflectionTestUtils.setField(service, "conversationStateService", stateService);
        ReflectionTestUtils.setField(service, "contextRewriter", rewriter);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        when(memoryService.resolveChatId(any())).thenReturn("test-chat");
        when(memoryService.load("test-chat")).thenReturn(Collections.emptyList());
        AiChatSession state = new AiChatSession();
        state.setChatId("test-chat");
        state.setLastDecisionSessionId(36L);
        when(stateService.getOrCreate("test-chat")).thenReturn(state);
        DecisionResponse completed = new DecisionResponse();
        completed.setSessionId(36L);
        completed.setStatus("COMPLETED");
        when(decisionService.getDecision(36L)).thenReturn(completed);
        com.hmdp.ai.dto.AgentSessionContext context = new com.hmdp.ai.dto.AgentSessionContext();
        com.hmdp.ai.dto.DecisionRecommendation candidate = new com.hmdp.ai.dto.DecisionRecommendation();
        candidate.setShopId(9L); candidate.setShopName("筑地日本料理（上街店）");
        context.getCandidatePoolSnapshot().add(candidate);
        context.getShownShopIdsSnapshot().add(9L);
        when(stateService.agentContext(state)).thenReturn(context);
        com.hmdp.ai.dto.ContextRewriteResult rewrite = new com.hmdp.ai.dto.ContextRewriteResult();
        rewrite.setOriginalQuery("第二家怎么样？");
        rewrite.setRewrittenQuery("筑地日本料理（上街店）怎么样？");
        rewrite.setApplied(true);
        rewrite.setUsedModel(true);
        rewrite.setReason("ELLIPSIS_RESOLVED");
        when(rewriter.rewrite(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(rewrite);
        when(conversationService.hasCandidateReference(org.mockito.Mockito.eq(rewrite.getRewrittenQuery()), any())).thenReturn(true);
        AgentConversationResponse conversation = new AgentConversationResponse();
        conversation.setAnswer("筑地日本料理（上街店）的评价如下。");
        when(conversationService.converse(org.mockito.Mockito.eq(36L), any(), any())).thenReturn(conversation);

        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("第二家怎么样？");
        ChatMessageResponse response = service.chat(request);

        ArgumentCaptor<com.hmdp.ai.dto.AgentConversationRequest> followUp =
                ArgumentCaptor.forClass(com.hmdp.ai.dto.AgentConversationRequest.class);
        verify(conversationService).converse(org.mockito.Mockito.eq(36L), followUp.capture(), any());
        assertEquals(rewrite.getRewrittenQuery(), followUp.getValue().getMessage());
        verify(memoryService).appendTurn(org.mockito.Mockito.eq("test-chat"),
                org.mockito.Mockito.eq("第二家怎么样？"), any(), any(), any());
        verify(decisionService, org.mockito.Mockito.never()).decide(any());
        assertTrue(response.getContextRewrite().getApplied());
    }

    @Test
    void refreshesRecommendationsWithUnseenShopsWhenUserAsksToChangeSeveralShops() {
        ChatOrchestrationService service = new ChatOrchestrationService();
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        ConversationStateService stateService = mock(ConversationStateService.class);
        ConversationContextRewriter rewriter = new ConversationContextRewriter();
        QueryRewriteClient queryRewriteClient = mock(QueryRewriteClient.class);
        ReflectionTestUtils.setField(rewriter, "queryRewriteClient", queryRewriteClient);
        ConstraintExtractor extractor = mock(ConstraintExtractor.class);
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        ReflectionTestUtils.setField(service, "conversationService", mock(AgentConversationService.class));
        ReflectionTestUtils.setField(service, "chatMemoryService", memoryService);
        ReflectionTestUtils.setField(service, "conversationStateService", stateService);
        ReflectionTestUtils.setField(service, "contextRewriter", rewriter);
        ReflectionTestUtils.setField(service, "constraintExtractor", extractor);
        ReflectionTestUtils.setField(service, "criteriaMerger", new ConversationCriteriaMerger());
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        when(memoryService.resolveChatId(any())).thenReturn("test-chat");
        when(memoryService.load("test-chat")).thenReturn(Collections.emptyList());
        AiChatSession state = new AiChatSession();
        state.setChatId("test-chat"); state.setActiveDecisionSessionId(36L);
        when(stateService.getOrCreate("test-chat")).thenReturn(state);
        when(stateService.slots(state)).thenReturn(new ConversationSlots());
        com.hmdp.ai.dto.ConversationWorkingMemory memory = new com.hmdp.ai.dto.ConversationWorkingMemory();
        com.hmdp.ai.dto.DecisionRecommendation first = new com.hmdp.ai.dto.DecisionRecommendation();
        first.setShopId(9L); first.setAvgPrice(120L);
        com.hmdp.ai.dto.DecisionRecommendation second = new com.hmdp.ai.dto.DecisionRecommendation();
        second.setShopId(10L); second.setAvgPrice(80L);
        com.hmdp.ai.dto.DecisionRecommendation third = new com.hmdp.ai.dto.DecisionRecommendation();
        third.setShopId(11L); third.setAvgPrice(95L);
        memory.setCandidatePool(Collections.singletonList(third));
        memory.setShownShopIds(Arrays.asList(9L, 10L, 11L));
        memory.setFocusedShopId(9L);
        when(stateService.workingMemory(state)).thenReturn(memory);
        com.hmdp.ai.dto.AgentSessionContext agentContext = new com.hmdp.ai.dto.AgentSessionContext();
        agentContext.setCandidatePoolSnapshot(Arrays.asList(first, second, third));
        agentContext.setFocusedShopId(9L);
        when(stateService.agentContext(state)).thenReturn(agentContext);
        DecisionResponse previous = new DecisionResponse();
        previous.setSessionId(36L); previous.setStatus("COMPLETED");
        when(decisionService.getDecision(36L)).thenReturn(previous);
        when(extractor.extract(anyString())).thenReturn(new DecisionConstraints());
        DecisionResponse next = new DecisionResponse();
        next.setSessionId(37L); next.setStatus("COMPLETED"); next.setAnswer("新的推荐");
        when(decisionService.decide(any(DecisionRequest.class), any(DecisionConstraints.class), any(), any())).thenReturn(next);

        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("多推荐几家不同的");
        ChatMessageResponse response = service.chat(request);

        ArgumentCaptor<DecisionRequest> decisionRequest = ArgumentCaptor.forClass(DecisionRequest.class);
        ArgumentCaptor<DecisionConstraints> mergedConstraints = ArgumentCaptor.forClass(DecisionConstraints.class);
        verify(decisionService).decide(decisionRequest.capture(), mergedConstraints.capture(), any(), any());
        assertEquals("START_DECISION", response.getRoute());
        assertTrue(decisionRequest.getValue().getQuery() != null && !decisionRequest.getValue().getQuery().isBlank());
        assertEquals(5, decisionRequest.getValue().getMaxCandidates());
        assertEquals(Arrays.asList(9L, 10L, 11L), decisionRequest.getValue().getExcludeShopIds());
        verifyNoInteractions(queryRewriteClient);
    }

    @Test
    void keepsGeneralChatOutsideDecisionWorkflowWhenModelIsUnavailable() {
        ChatOrchestrationService service = new ChatOrchestrationService();
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        AgentConversationService conversationService = mock(AgentConversationService.class);
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        ConversationStateService sessionStateService = mock(ConversationStateService.class);
        ReflectionTestUtils.setField(service, "aiClient", mock(OpenAiCompatibleClient.class));
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        ReflectionTestUtils.setField(service, "conversationService", conversationService);
        ReflectionTestUtils.setField(service, "chatMemoryService", memoryService);
        ReflectionTestUtils.setField(service, "conversationStateService", sessionStateService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        when(memoryService.resolveChatId(any())).thenReturn("test-chat");
        when(memoryService.load("test-chat")).thenReturn(Collections.emptyList());
        when(sessionStateService.getOrCreate("test-chat")).thenReturn(new AiChatSession());
        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("hello 啊");

        ChatMessageResponse response = service.chat(request);

        assertEquals("GENERAL_CHAT", response.getRoute());
        assertEquals("test-chat", response.getChatId());
        assertTrue(response.getAnswer().contains("消费决策助手"));
        verifyNoInteractions(decisionService, conversationService);
        verify(memoryService).appendTurn(any(), any(), any(), any(), any());
    }

    @Test
    void explainsNoDataSuspensionWithoutStartingNewDecisionOrBusinessFollowUp() {
        ChatOrchestrationService service = new ChatOrchestrationService();
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        AgentConversationService conversationService = mock(AgentConversationService.class);
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        ConversationStateService stateService = mock(ConversationStateService.class);
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        ReflectionTestUtils.setField(service, "conversationService", conversationService);
        ReflectionTestUtils.setField(service, "chatMemoryService", memoryService);
        ReflectionTestUtils.setField(service, "conversationStateService", stateService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        when(memoryService.resolveChatId(any())).thenReturn("test-chat");
        when(memoryService.load("test-chat")).thenReturn(Collections.emptyList());
        AiChatSession state = new AiChatSession();
        state.setChatId("test-chat");
        state.setActiveDecisionSessionId(100L);
        when(stateService.getOrCreate("test-chat")).thenReturn(state);
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setTargetCity("重庆");
        DecisionResponse paused = new DecisionResponse();
        paused.setSessionId(100L);
        paused.setStatus("ZERO_RESULT_NO_DATA");
        paused.setConstraints(constraints);
        when(decisionService.getDecision(100L)).thenReturn(paused);

        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("我刚刚不是说要重庆吗？");
        ChatMessageResponse response = service.chat(request);

        assertEquals("EXPLAIN_SUSPENDED_DECISION", response.getRoute());
        assertEquals(100L, response.getDecisionSessionId());
        assertTrue(response.getAnswer().contains("重庆"));
        assertTrue(response.getAnswer().contains("暂无入库商户"));
        verify(decisionService, never()).decide(any());
        verifyNoInteractions(conversationService);
    }

    @Test
    void modelCanRouteUnseenSuspendedQuestionToExplanation() throws Exception {
        ChatOrchestrationService service = new ChatOrchestrationService();
        OpenAiCompatibleClient aiClient = mock(OpenAiCompatibleClient.class);
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        ConversationStateService stateService = mock(ConversationStateService.class);
        AiProperties properties = new AiProperties();
        properties.setApiKey("test-key");
        ReflectionTestUtils.setField(service, "aiClient", aiClient);
        ReflectionTestUtils.setField(service, "aiProperties", properties);
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        ReflectionTestUtils.setField(service, "conversationService", mock(AgentConversationService.class));
        ReflectionTestUtils.setField(service, "chatMemoryService", memoryService);
        ReflectionTestUtils.setField(service, "conversationStateService", stateService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        when(memoryService.resolveChatId(any())).thenReturn("test-chat");
        when(memoryService.load("test-chat")).thenReturn(Collections.emptyList());
        AiChatSession state = new AiChatSession();
        state.setChatId("test-chat");
        state.setActiveDecisionSessionId(100L);
        when(stateService.getOrCreate("test-chat")).thenReturn(state);
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setTargetCity("重庆");
        DecisionResponse paused = new DecisionResponse();
        paused.setSessionId(100L);
        paused.setStatus("ZERO_RESULT_NO_DATA");
        paused.setConstraints(constraints);
        when(decisionService.getDecision(100L)).thenReturn(paused);
        when(aiClient.chatCompletion(any(), any(), any(), any())).thenReturn(new ObjectMapper().readTree(
                "{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"route\\\":\\\"EXPLAIN_SUSPENDED_DECISION\\\"}\"}}]}}]}"));

        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("是哪里不匹配？");
        ChatMessageResponse response = service.chat(request);

        assertEquals("EXPLAIN_SUSPENDED_DECISION", response.getRoute());
        assertTrue(response.getAnswer().contains("重庆"));
        verify(decisionService, never()).decide(any());
    }

    @Test
    void restoresLastDecisionForBusinessFollowUpWhenClientSessionIsMissing() throws Exception {
        ChatOrchestrationService service = new ChatOrchestrationService();
        OpenAiCompatibleClient aiClient = mock(OpenAiCompatibleClient.class);
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        AgentConversationService conversationService = mock(AgentConversationService.class);
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        ConversationStateService sessionStateService = mock(ConversationStateService.class);
        AiProperties properties = new AiProperties();
        properties.setApiKey("test-key");
        ReflectionTestUtils.setField(service, "aiClient", aiClient);
        ReflectionTestUtils.setField(service, "aiProperties", properties);
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        ReflectionTestUtils.setField(service, "conversationService", conversationService);
        ReflectionTestUtils.setField(service, "chatMemoryService", memoryService);
        ReflectionTestUtils.setField(service, "conversationStateService", sessionStateService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        when(memoryService.resolveChatId(any())).thenReturn("test-chat");
        when(memoryService.load("test-chat")).thenReturn(Collections.emptyList());
        AiChatSession state = new AiChatSession();
        state.setChatId("test-chat");
        state.setLastDecisionSessionId(36L);
        when(sessionStateService.getOrCreate("test-chat")).thenReturn(state);
        when(aiClient.chatCompletion(any(), any(), any(), any())).thenReturn(new ObjectMapper().readTree(
                "{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"route\\\":\\\"BUSINESS_FOLLOW_UP\\\"}\"}}]}}]}"));
        DecisionResponse decision = new DecisionResponse();
        decision.setSessionId(36L);
        decision.setStatus("COMPLETED");
        when(decisionService.getDecision(36L)).thenReturn(decision);
        AgentConversationResponse conversation = new AgentConversationResponse();
        conversation.setAnswer("已查询到本地评价证据。");
        when(conversationService.converse(any(), any(), any())).thenReturn(conversation);
        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("那一家评价如何");

        ChatMessageResponse response = service.chat(request);

        assertEquals("BUSINESS_FOLLOW_UP", response.getRoute());
        assertEquals(36L, response.getDecisionSessionId());
        assertEquals("COMPLETED", response.getDecisionStatus());
        verify(conversationService).converse(any(), any(), any());
        verify(sessionStateService, org.mockito.Mockito.never()).rememberLastDecision(state, 36L);
    }

    @Test
    void candidateReferenceOverridesModelAndStaysInBusinessFollowUp() {
        ChatOrchestrationService service = new ChatOrchestrationService();
        OpenAiCompatibleClient aiClient = mock(OpenAiCompatibleClient.class);
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        AgentConversationService conversationService = mock(AgentConversationService.class);
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        ConversationStateService stateService = mock(ConversationStateService.class);
        ReflectionTestUtils.setField(service, "aiClient", aiClient);
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        ReflectionTestUtils.setField(service, "conversationService", conversationService);
        ReflectionTestUtils.setField(service, "chatMemoryService", memoryService);
        ReflectionTestUtils.setField(service, "conversationStateService", stateService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        when(memoryService.resolveChatId(any())).thenReturn("test-chat");
        when(memoryService.load("test-chat")).thenReturn(Collections.emptyList());
        AiChatSession state = new AiChatSession();
        state.setChatId("test-chat");
        state.setLastDecisionSessionId(36L);
        when(stateService.getOrCreate("test-chat")).thenReturn(state);
        DecisionResponse completed = new DecisionResponse();
        completed.setSessionId(36L);
        completed.setStatus("COMPLETED");
        when(decisionService.getDecision(36L)).thenReturn(completed);
        when(conversationService.hasCandidateReference(org.mockito.Mockito.eq("这个日本料理怎么样"), any())).thenReturn(true);
        AgentConversationResponse conversation = new AgentConversationResponse();
        conversation.setAnswer("筑地日本料理（上街店）的评价如下。");
        when(conversationService.converse(any(), any(), any())).thenReturn(conversation);

        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("这个日本料理怎么样");
        ChatMessageResponse response = service.chat(request);

        assertEquals("BUSINESS_FOLLOW_UP", response.getRoute());
        assertEquals("筑地日本料理（上街店）的评价如下。", response.getAnswer());
        verify(conversationService).converse(any(), any(), any());
        verifyNoInteractions(aiClient);
    }

    @Test
    void newRestaurantNeedSupersedesPausedDecisionAndReusesLocation() {
        ChatOrchestrationService service = new ChatOrchestrationService();
        OpenAiCompatibleClient aiClient = mock(OpenAiCompatibleClient.class);
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        ConversationStateService stateService = mock(ConversationStateService.class);
        AgentConversationService conversationService = mock(AgentConversationService.class);
        ReflectionTestUtils.setField(service, "aiClient", aiClient);
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        ReflectionTestUtils.setField(service, "conversationService", conversationService);
        ReflectionTestUtils.setField(service, "chatMemoryService", memoryService);
        ReflectionTestUtils.setField(service, "conversationStateService", stateService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        when(memoryService.resolveChatId(any())).thenReturn("test-chat");
        when(memoryService.load("test-chat")).thenReturn(Collections.emptyList());
        AiChatSession state = new AiChatSession();
        state.setChatId("test-chat");
        state.setActiveDecisionSessionId(100L);
        when(stateService.getOrCreate("test-chat")).thenReturn(state);
        ConversationLocationSlot location = new ConversationLocationSlot();
        location.setStatus("AVAILABLE");
        location.setLatitude(26.054D);
        location.setLongitude(119.186D);
        when(stateService.usableLocation(state)).thenReturn(location);
        DecisionResponse paused = new DecisionResponse();
        paused.setSessionId(100L);
        paused.setStatus("WAITING_RELAXATION");
        when(decisionService.getDecision(100L)).thenReturn(paused);
        DecisionResponse cancelled = new DecisionResponse();
        cancelled.setSessionId(100L);
        cancelled.setStatus("CANCELLED");
        when(decisionService.continueDecision(org.mockito.Mockito.eq(100L), any())).thenReturn(cancelled);
        DecisionResponse started = new DecisionResponse();
        started.setSessionId(101L);
        started.setStatus("COMPLETED");
        started.setAnswer("已找到附近烤肉店");
        when(decisionService.decide(any())).thenReturn(started);

        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("那烤肉店呢");
        ChatMessageResponse response = service.chat(request);

        ArgumentCaptor<DecisionFollowUpRequest> cancellation = ArgumentCaptor.forClass(DecisionFollowUpRequest.class);
        verify(decisionService).continueDecision(org.mockito.Mockito.eq(100L), cancellation.capture());
        assertEquals("END_DECISION", cancellation.getValue().getSelectedOptionId());
        assertEquals("START_DECISION", response.getRoute());
        assertEquals(101L, response.getDecisionSessionId());
        verify(stateService).clearActiveDecision(state);
        verify(stateService).activateDecision(state, 101L);
        verifyNoInteractions(aiClient);
    }

    @Test
    void resumesClarifyingDecisionFromPersistedLocationEventWithoutRoutingModel() {
        ChatOrchestrationService service = new ChatOrchestrationService();
        OpenAiCompatibleClient aiClient = mock(OpenAiCompatibleClient.class);
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        ConversationStateService stateService = mock(ConversationStateService.class);
        ReflectionTestUtils.setField(service, "aiClient", aiClient);
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        ReflectionTestUtils.setField(service, "conversationService", mock(AgentConversationService.class));
        ReflectionTestUtils.setField(service, "chatMemoryService", memoryService);
        ReflectionTestUtils.setField(service, "conversationStateService", stateService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        when(memoryService.resolveChatId(any())).thenReturn("test-chat");
        when(memoryService.load("test-chat")).thenReturn(Collections.emptyList());
        AiChatSession state = new AiChatSession();
        state.setChatId("test-chat");
        state.setActiveDecisionSessionId(100L);
        when(stateService.getOrCreate("test-chat")).thenReturn(state);
        ConversationLocationSlot location = new ConversationLocationSlot();
        location.setStatus("AVAILABLE");
        location.setLatitude(26.0745D);
        location.setLongitude(119.1978D);
        when(stateService.usableLocation(state)).thenReturn(location);
        DecisionResponse clarifying = new DecisionResponse();
        clarifying.setSessionId(100L);
        clarifying.setStatus("CLARIFYING");
        when(decisionService.getDecision(100L)).thenReturn(clarifying);
        DecisionResponse completed = new DecisionResponse();
        completed.setSessionId(100L);
        completed.setStatus("COMPLETED");
        completed.setAnswer("已按当前位置完成推荐");
        when(decisionService.continueDecision(any(), any())).thenReturn(completed);
        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("已提供当前位置");
        request.setSelectedOptionId("PROVIDE_LOCATION");
        ChatLocationInput input = new ChatLocationInput();
        input.setLatitude(26.0745D);
        input.setLongitude(119.1978D);
        input.setSource("BROWSER_GEOLOCATION");
        request.setLocation(input);

        ChatMessageResponse response = service.chat(request);

        assertEquals("DECISION_EVENT", response.getRoute());
        assertEquals("COMPLETED", response.getDecisionStatus());
        verify(stateService).acceptLocation(state, input);
        verify(decisionService).continueDecision(any(), any());
        verifyNoInteractions(aiClient);
    }

    @Test
    void explicitPlaceDoesNotReusePreviousBrowserLocation() {
        ChatOrchestrationService service = new ChatOrchestrationService();
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        ConversationStateService stateService = mock(ConversationStateService.class);
        ReflectionTestUtils.setField(service, "aiClient", mock(OpenAiCompatibleClient.class));
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        ReflectionTestUtils.setField(service, "conversationService", mock(AgentConversationService.class));
        ReflectionTestUtils.setField(service, "chatMemoryService", memoryService);
        ReflectionTestUtils.setField(service, "conversationStateService", stateService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        when(memoryService.resolveChatId(any())).thenReturn("test-chat");
        when(memoryService.load("test-chat")).thenReturn(Collections.emptyList());
        AiChatSession state = new AiChatSession();
        state.setChatId("test-chat");
        when(stateService.getOrCreate("test-chat")).thenReturn(state);
        DecisionResponse decision = new DecisionResponse();
        decision.setSessionId(57L);
        decision.setStatus("CLARIFYING");
        decision.setOptions(new java.util.ArrayList<>());
        when(decisionService.decide(any())).thenReturn(decision);

        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("帮我找重庆附近的火锅");
        ChatMessageResponse response = service.chat(request);

        ArgumentCaptor<DecisionRequest> decisionRequest = ArgumentCaptor.forClass(DecisionRequest.class);
        verify(decisionService).decide(decisionRequest.capture());
        assertEquals(null, decisionRequest.getValue().getLatitude());
        assertEquals("START_DECISION", response.getRoute());
        assertEquals(57L, response.getDecisionSessionId());
    }

    @Test
    @org.junit.jupiter.api.Disabled("Gateway location regex extraction was removed in favor of the structured NLU contract.")
    void nearbySearchPhraseDoesNotTreatActionWordsAsAnExplicitLocation() {
        ChatOrchestrationService service = new ChatOrchestrationService();
        AmapMcpLocationResolutionService locationService = mock(AmapMcpLocationResolutionService.class);
        ReflectionTestUtils.setField(service, "locationResolutionService", locationService);
        when(locationService.isAvailable()).thenReturn(true);
        com.hmdp.ai.dto.DecisionConstraints nearby = new com.hmdp.ai.dto.DecisionConstraints();
        com.hmdp.ai.dto.DecisionConstraints namedCity = new com.hmdp.ai.dto.DecisionConstraints();
        namedCity.setTargetCity("重庆");
        com.hmdp.ai.dto.DecisionConstraints namedArea = new com.hmdp.ai.dto.DecisionConstraints();
        namedArea.setTargetArea("解放碑");

        assertNull(ReflectionTestUtils.invokeMethod(service, "locationResolutionScope", nearby));
        assertNull(ReflectionTestUtils.invokeMethod(service, "locationResolutionScope", namedCity));
        assertEquals("解放碑", ReflectionTestUtils.invokeMethod(service, "locationResolutionScope", namedArea));
        assertEquals(false, ReflectionTestUtils.invokeMethod(service, "isPotentialNamedLocation", "这家营业到几点"));
        assertEquals(true, ReflectionTestUtils.invokeMethod(service, "isPotentialNamedLocation", "福州鼓楼区"));
        assertEquals(true, ReflectionTestUtils.invokeMethod(service, "refersToCurrentDeviceLocation", "我刚飞到福州，按我现在位置重新推荐"));
        assertEquals(false, ReflectionTestUtils.invokeMethod(service, "refersToCurrentDeviceLocation", "帮我找重庆附近的火锅"));
    }

    @Test
    void cancelledGroupMealAndNewQuickMealIsRecognizedAsNewRecommendation() {
        ChatOrchestrationService service = new ChatOrchestrationService();

        assertTrue(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(service, "isNewRecommendationIntent",
                "聚会先取消了，我自己一个人吃点简餐快餐就行")));
    }

    @Test
    void explicitPlaceDoesNotDependOnMapAvailabilityWhenCityIsStructured() {
        ChatOrchestrationService service = new ChatOrchestrationService();
        OpenAiCompatibleClient aiClient = mock(OpenAiCompatibleClient.class);
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        ConversationStateService stateService = mock(ConversationStateService.class);
        ReflectionTestUtils.setField(service, "aiClient", aiClient);
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        ReflectionTestUtils.setField(service, "conversationService", mock(AgentConversationService.class));
        ReflectionTestUtils.setField(service, "chatMemoryService", memoryService);
        ReflectionTestUtils.setField(service, "conversationStateService", stateService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        when(memoryService.resolveChatId(any())).thenReturn("test-chat");
        when(memoryService.load("test-chat")).thenReturn(Collections.emptyList());
        AiChatSession state = new AiChatSession();
        state.setChatId("test-chat");
        when(stateService.getOrCreate("test-chat")).thenReturn(state);
        DecisionResponse decision = new DecisionResponse();
        decision.setSessionId(58L);
        decision.setStatus("CLARIFYING");
        decision.setOptions(new java.util.ArrayList<>());
        when(decisionService.decide(any())).thenReturn(decision);

        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("帮我找重庆附近的火锅");
        ChatMessageResponse response = service.chat(request);

        ArgumentCaptor<DecisionRequest> decisionRequest = ArgumentCaptor.forClass(DecisionRequest.class);
        verify(decisionService).decide(decisionRequest.capture());
        assertNull(decisionRequest.getValue().getLatitude());
        assertEquals("START_DECISION", response.getRoute());
    }

    @Test
    void sceneBasedRestaurantRequestStartsNewDecisionInsteadOfBusinessFollowUp() {
        ChatOrchestrationService service = new ChatOrchestrationService();
        OpenAiCompatibleClient aiClient = mock(OpenAiCompatibleClient.class);
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        ConversationStateService stateService = mock(ConversationStateService.class);
        AgentConversationService conversationService = mock(AgentConversationService.class);
        ReflectionTestUtils.setField(service, "aiClient", aiClient);
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        ReflectionTestUtils.setField(service, "conversationService", conversationService);
        ReflectionTestUtils.setField(service, "chatMemoryService", memoryService);
        ReflectionTestUtils.setField(service, "conversationStateService", stateService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        when(memoryService.resolveChatId(any())).thenReturn("test-chat");
        when(memoryService.load("test-chat")).thenReturn(Collections.emptyList());
        AiChatSession state = new AiChatSession();
        state.setChatId("test-chat");
        state.setActiveDecisionSessionId(55L);
        when(stateService.getOrCreate("test-chat")).thenReturn(state);
        ConversationSlots slots = new ConversationSlots();
        when(stateService.slots(state)).thenReturn(slots);
        DecisionResponse previous = new DecisionResponse();
        previous.setSessionId(55L);
        previous.setStatus("COMPLETED");
        when(decisionService.getDecision(55L)).thenReturn(previous);
        // The previous candidate pool may still resolve "那家" references, but it must not
        // override an explicit request to start a new recommendation.
        when(conversationService.hasCandidateReference(org.mockito.Mockito.eq("那有适合约会的店吗"), any())).thenReturn(true);
        DecisionResponse next = new DecisionResponse();
        next.setSessionId(58L);
        next.setStatus("COMPLETED");
        next.setAnswer("已按约会场景开始重新筛选");
        when(decisionService.decide(any())).thenReturn(next);

        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("那有适合约会的店吗");
        ChatMessageResponse response = service.chat(request);

        assertEquals("START_DECISION", response.getRoute());
        assertEquals(58L, response.getDecisionSessionId());
        verify(decisionService).decide(any());
        verifyNoInteractions(aiClient);
    }

    @Test
    void currentDeviceContinuationAfterNoDataStartsNewDecisionWithoutRouterModel() {
        ChatOrchestrationService service = new ChatOrchestrationService();
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        ConversationStateService stateService = mock(ConversationStateService.class);
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        ReflectionTestUtils.setField(service, "conversationService", mock(AgentConversationService.class));
        ReflectionTestUtils.setField(service, "chatMemoryService", memoryService);
        ReflectionTestUtils.setField(service, "conversationStateService", stateService);
        ReflectionTestUtils.setField(service, "contextRewriter", new ConversationContextRewriter());
        DecisionConstraints extracted = new DecisionConstraints();
        extracted.setLocationIntent("CURRENT_DEVICE"); extracted.setNearby(true);
        ConstraintExtractor extractor = mock(ConstraintExtractor.class);
        when(extractor.extract(anyString())).thenReturn(extracted);
        ReflectionTestUtils.setField(service, "constraintExtractor", extractor);
        ReflectionTestUtils.setField(service, "criteriaMerger", new ConversationCriteriaMerger());
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        when(memoryService.resolveChatId(any())).thenReturn("test-chat");
        when(memoryService.load("test-chat")).thenReturn(Collections.emptyList());
        AiChatSession state = new AiChatSession();
        state.setChatId("test-chat"); state.setActiveDecisionSessionId(100L);
        when(stateService.getOrCreate("test-chat")).thenReturn(state);
        when(stateService.agentContext(state)).thenReturn(new com.hmdp.ai.dto.AgentSessionContext());
        com.hmdp.ai.dto.ConversationWorkingMemory memory = new com.hmdp.ai.dto.ConversationWorkingMemory();
        memory.getActiveCriteria().setTargetCity("北京"); memory.getActiveCriteria().setLocationIntent("EXPLICIT_TARGET");
        when(stateService.workingMemory(state)).thenReturn(memory);
        when(stateService.slots(state)).thenReturn(new ConversationSlots());
        DecisionResponse paused = new DecisionResponse();
        paused.setSessionId(100L); paused.setStatus("ZERO_RESULT_NO_DATA");
        when(decisionService.getDecision(100L)).thenReturn(paused);
        DecisionResponse started = new DecisionResponse();
        started.setSessionId(101L); started.setStatus("CLARIFYING"); started.setAnswer("请提供当前位置");
        when(decisionService.decide(any(), any(), any(), any())).thenReturn(started);

        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("我附近呢");
        ChatMessageResponse response = service.chat(request);

        assertEquals("START_DECISION", response.getRoute());
        assertEquals("CLARIFYING", response.getDecisionStatus());
        assertEquals("CURRENT_DEVICE_LOCATION_CONTINUATION", response.getContextRewrite().getReason());
        verify(decisionService).continueDecision(org.mockito.Mockito.eq(100L), any());
        verify(decisionService).decide(any(), any(), any(), any());
    }

    @Test
    void nonDiningDomainGuardBlocksBadmintonRequest() {
        // 修复 #29：无餐饮强信号 + 命中非餐饮领域词表（羽毛球）→ 判定领域外
        ChatOrchestrationService service = new ChatOrchestrationService();
        Boolean blocked = ReflectionTestUtils.invokeMethod(service, "isNonDiningDomain", "帮我看看附近有没有羽毛球馆");
        assertTrue(Boolean.TRUE.equals(blocked));
    }

    @Test
    void nonDiningDomainGuardDoesNotBlockCompoundDiningRequest() {
        // 修复 #29：复合句含餐饮强信号（吃/火锅）→ 不应被领域守卫拦截
        ChatOrchestrationService service = new ChatOrchestrationService();
        Boolean blocked = ReflectionTestUtils.invokeMethod(service, "isNonDiningDomain", "打完羽毛球去吃火锅");
        assertFalse(Boolean.TRUE.equals(blocked));
    }

    @Test
    void locationSearchContinuationRequiresDiningSignal() {
        // 修复 #29（方案A）："附近+看看" 无餐饮语义 → 不再判定为新推荐意图
        ChatOrchestrationService service = new ChatOrchestrationService();
        Boolean badminton = ReflectionTestUtils.invokeMethod(service, "isNewRecommendationIntent", "帮我看看附近有没有羽毛球馆");
        assertFalse(Boolean.TRUE.equals(badminton));
        // 带菜系（火锅）仍命中
        Boolean hotpot = ReflectionTestUtils.invokeMethod(service, "isNewRecommendationIntent", "帮我看看附近有没有火锅");
        assertTrue(Boolean.TRUE.equals(hotpot));
        // 方案A升级为 hasDiningSignal 后，"好吃的"（含"吃"）仍命中，避免 case28 误伤（2026-09-02）
        Boolean tasty = ReflectionTestUtils.invokeMethod(service, "isNewRecommendationIntent", "帮我看看附近有没有什么好吃的");
        assertTrue(Boolean.TRUE.equals(tasty));
    }

    @Test
    void nonDiningRequestIsRoutedToGeneralChatWithoutDecision() throws Exception {
        // 修复 #29（端到端）：非餐饮请求被领域守卫拦截为 GENERAL_CHAT，不进入餐饮决策
        ChatOrchestrationService service = new ChatOrchestrationService();
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        ConversationStateService stateService = mock(ConversationStateService.class);
        SpringAiTextClient textClient = mock(SpringAiTextClient.class);
        AiProperties properties = new AiProperties();
        properties.setApiKey("test-key");
        ReflectionTestUtils.setField(service, "aiProperties", properties);
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        ReflectionTestUtils.setField(service, "conversationService", mock(AgentConversationService.class));
        ReflectionTestUtils.setField(service, "chatMemoryService", memoryService);
        ReflectionTestUtils.setField(service, "conversationStateService", stateService);
        ReflectionTestUtils.setField(service, "springAiTextClient", textClient);
        ReflectionTestUtils.setField(service, "contextRewriter", new ConversationContextRewriter());
        ReflectionTestUtils.setField(service, "constraintExtractor", mock(ConstraintExtractor.class));
        ReflectionTestUtils.setField(service, "criteriaMerger", new ConversationCriteriaMerger());
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        when(memoryService.resolveChatId(any())).thenReturn("test-chat");
        when(memoryService.load("test-chat")).thenReturn(Collections.emptyList());
        AiChatSession state = new AiChatSession();
        state.setChatId("test-chat");
        when(stateService.getOrCreate("test-chat")).thenReturn(state);
        when(stateService.agentContext(state)).thenReturn(new com.hmdp.ai.dto.AgentSessionContext());
        when(textClient.chatText(any(), any())).thenReturn("运动场馆类需求暂时不在支持范围内，我可以帮你推荐餐厅。");

        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("帮我看看附近有没有羽毛球馆");
        ChatMessageResponse response = service.chat(request);

        assertEquals("GENERAL_CHAT", response.getRoute());
        // 未进入餐饮决策链路
        verifyNoInteractions(decisionService);
    }

    @Test
    void waitingRelaxationPureLocationRecoversDecisionWithProvidedLocation() {
        // B 修复 #case30：WAITING_RELAXATION 态"我附近" → 恢复（PROVIDE_LOCATION），保留约束换位置重搜。
        ChatOrchestrationService service = new ChatOrchestrationService();
        OpenAiCompatibleClient aiClient = mock(OpenAiCompatibleClient.class);
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        ConversationStateService stateService = mock(ConversationStateService.class);
        ReflectionTestUtils.setField(service, "aiClient", aiClient);
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        ReflectionTestUtils.setField(service, "conversationService", mock(AgentConversationService.class));
        ReflectionTestUtils.setField(service, "chatMemoryService", memoryService);
        ReflectionTestUtils.setField(service, "conversationStateService", stateService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        when(memoryService.resolveChatId(any())).thenReturn("test-chat");
        when(memoryService.load("test-chat")).thenReturn(Collections.emptyList());
        AiChatSession state = new AiChatSession();
        state.setChatId("test-chat");
        state.setActiveDecisionSessionId(100L);
        when(stateService.getOrCreate("test-chat")).thenReturn(state);
        ConversationLocationSlot location = new ConversationLocationSlot();
        location.setStatus("AVAILABLE");
        location.setLatitude(26.0745D);
        location.setLongitude(119.1978D);
        when(stateService.usableSearchLocation(state)).thenReturn(location);
        DecisionResponse suspended = new DecisionResponse();
        suspended.setSessionId(100L);
        suspended.setStatus("WAITING_RELAXATION");
        when(decisionService.getDecision(100L)).thenReturn(suspended);
        DecisionResponse resumed = new DecisionResponse();
        resumed.setSessionId(100L);
        resumed.setStatus("COMPLETED");
        resumed.setAnswer("已按当前位置重新搜索");
        when(decisionService.continueDecision(any(), any())).thenReturn(resumed);

        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("我附近");
        ChatMessageResponse response = service.chat(request);

        assertEquals("DECISION_EVENT", response.getRoute());
        ArgumentCaptor<DecisionFollowUpRequest> captor = ArgumentCaptor.forClass(DecisionFollowUpRequest.class);
        verify(decisionService).continueDecision(org.mockito.Mockito.eq(100L), captor.capture());
        assertEquals("PROVIDE_LOCATION", captor.getValue().getSelectedOptionId());
        assertEquals(26.0745D, captor.getValue().getLatitude());
        assertEquals(119.1978D, captor.getValue().getLongitude());
        verifyNoInteractions(aiClient);
    }

    @Test
    void locationRecoveryPredicateExcludesDiningOrDemandExpressions() {
        // 检查项②：窄谓词边界。纯位置 → true；含餐饮/需求词 → false（不被恢复分流吞掉）。
        ChatOrchestrationService service = new ChatOrchestrationService();
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "isLocationRecoveryExpression", "我附近"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "isLocationRecoveryExpression", "当前位置"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "isLocationRecoveryExpression", "就在我这"));
        // 完整新需求（含菜系/需求词）不得被恢复分流吞掉
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "isLocationRecoveryExpression", "福州附近的火锅"));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "isLocationRecoveryExpression", "我附近有没有火锅"));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "isLocationRecoveryExpression", "换一家"));
    }

    @Test
    void waitingRelaxationLocationPlusDiningStartsNewDecision() {
        // 检查项②：WAITING_RELAXATION + "我附近有没有火锅"（位置词+餐饮词）→ 显式新需求 → START_DECISION。
        ChatOrchestrationService service = new ChatOrchestrationService();
        OpenAiCompatibleClient aiClient = mock(OpenAiCompatibleClient.class);
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        ConversationStateService stateService = mock(ConversationStateService.class);
        AgentConversationService conversationService = mock(AgentConversationService.class);
        ReflectionTestUtils.setField(service, "aiClient", aiClient);
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        ReflectionTestUtils.setField(service, "conversationService", conversationService);
        ReflectionTestUtils.setField(service, "chatMemoryService", memoryService);
        ReflectionTestUtils.setField(service, "conversationStateService", stateService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        when(memoryService.resolveChatId(any())).thenReturn("test-chat");
        when(memoryService.load("test-chat")).thenReturn(Collections.emptyList());
        AiChatSession state = new AiChatSession();
        state.setChatId("test-chat");
        state.setActiveDecisionSessionId(100L);
        when(stateService.getOrCreate("test-chat")).thenReturn(state);
        DecisionResponse paused = new DecisionResponse();
        paused.setSessionId(100L);
        paused.setStatus("WAITING_RELAXATION");
        when(decisionService.getDecision(100L)).thenReturn(paused);
        DecisionResponse cancelled = new DecisionResponse();
        cancelled.setSessionId(100L);
        cancelled.setStatus("CANCELLED");
        when(decisionService.continueDecision(org.mockito.Mockito.eq(100L), any())).thenReturn(cancelled);
        DecisionResponse started = new DecisionResponse();
        started.setSessionId(101L);
        started.setStatus("COMPLETED");
        when(decisionService.decide(any())).thenReturn(started);

        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("我附近有没有火锅");
        ChatMessageResponse response = service.chat(request);

        assertEquals("START_DECISION", response.getRoute());
        ArgumentCaptor<DecisionFollowUpRequest> cancellation = ArgumentCaptor.forClass(DecisionFollowUpRequest.class);
        verify(decisionService).continueDecision(org.mockito.Mockito.eq(100L), cancellation.capture());
        assertEquals("END_DECISION", cancellation.getValue().getSelectedOptionId());
        verifyNoInteractions(aiClient);
    }

    @Test
    void waitingRelaxationDemandSwitchStartsNewDecision() {
        // 空洞1：WAITING_RELAXATION × 换需求（"重新推荐"）→ START_DECISION 重开，而非追问候选池。
        ChatOrchestrationService service = new ChatOrchestrationService();
        OpenAiCompatibleClient aiClient = mock(OpenAiCompatibleClient.class);
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        ConversationStateService stateService = mock(ConversationStateService.class);
        AgentConversationService conversationService = mock(AgentConversationService.class);
        ReflectionTestUtils.setField(service, "aiClient", aiClient);
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        ReflectionTestUtils.setField(service, "conversationService", conversationService);
        ReflectionTestUtils.setField(service, "chatMemoryService", memoryService);
        ReflectionTestUtils.setField(service, "conversationStateService", stateService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        when(memoryService.resolveChatId(any())).thenReturn("test-chat");
        when(memoryService.load("test-chat")).thenReturn(Collections.emptyList());
        AiChatSession state = new AiChatSession();
        state.setChatId("test-chat");
        state.setActiveDecisionSessionId(100L);
        when(stateService.getOrCreate("test-chat")).thenReturn(state);
        DecisionResponse paused = new DecisionResponse();
        paused.setSessionId(100L);
        paused.setStatus("WAITING_RELAXATION");
        when(decisionService.getDecision(100L)).thenReturn(paused);
        DecisionResponse cancelled = new DecisionResponse();
        cancelled.setSessionId(100L);
        cancelled.setStatus("CANCELLED");
        when(decisionService.continueDecision(org.mockito.Mockito.eq(100L), any())).thenReturn(cancelled);
        DecisionResponse started = new DecisionResponse();
        started.setSessionId(101L);
        started.setStatus("COMPLETED");
        when(decisionService.decide(any())).thenReturn(started);

        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("重新推荐");
        ChatMessageResponse response = service.chat(request);

        assertEquals("START_DECISION", response.getRoute());
        ArgumentCaptor<DecisionFollowUpRequest> cancellation = ArgumentCaptor.forClass(DecisionFollowUpRequest.class);
        verify(decisionService).continueDecision(org.mockito.Mockito.eq(100L), cancellation.capture());
        assertEquals("END_DECISION", cancellation.getValue().getSelectedOptionId());
        verifyNoInteractions(aiClient);
    }

    @Test
    void clarifyingDemandSwitchStartsNewDecision() {
        // 空洞3：CLARIFYING × 换需求（"算了换一家"）→ START_DECISION 重开，而非卡在位置澄清。
        // replacesPausedDecision 的 isPausedDecision 覆盖 CLARIFYING，此处补确定性测试。
        ChatOrchestrationService service = new ChatOrchestrationService();
        OpenAiCompatibleClient aiClient = mock(OpenAiCompatibleClient.class);
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        ConversationStateService stateService = mock(ConversationStateService.class);
        AgentConversationService conversationService = mock(AgentConversationService.class);
        ReflectionTestUtils.setField(service, "aiClient", aiClient);
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        ReflectionTestUtils.setField(service, "conversationService", conversationService);
        ReflectionTestUtils.setField(service, "chatMemoryService", memoryService);
        ReflectionTestUtils.setField(service, "conversationStateService", stateService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        AmapMcpLocationResolutionService locationResolutionService = mock(AmapMcpLocationResolutionService.class);
        ReflectionTestUtils.setField(service, "locationResolutionService", locationResolutionService);
        when(memoryService.resolveChatId(any())).thenReturn("test-chat");
        when(memoryService.load("test-chat")).thenReturn(Collections.emptyList());
        AiChatSession state = new AiChatSession();
        state.setChatId("test-chat");
        state.setActiveDecisionSessionId(100L);
        when(stateService.getOrCreate("test-chat")).thenReturn(state);
        DecisionResponse paused = new DecisionResponse();
        paused.setSessionId(100L);
        paused.setStatus("CLARIFYING");
        when(decisionService.getDecision(100L)).thenReturn(paused);
        DecisionResponse cancelled = new DecisionResponse();
        cancelled.setSessionId(100L);
        cancelled.setStatus("CANCELLED");
        when(decisionService.continueDecision(org.mockito.Mockito.eq(100L), any())).thenReturn(cancelled);
        DecisionResponse started = new DecisionResponse();
        started.setSessionId(101L);
        started.setStatus("COMPLETED");
        when(decisionService.decide(any())).thenReturn(started);

        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("算了换一家");
        ChatMessageResponse response = service.chat(request);

        assertEquals("START_DECISION", response.getRoute());
        ArgumentCaptor<DecisionFollowUpRequest> cancellation = ArgumentCaptor.forClass(DecisionFollowUpRequest.class);
        verify(decisionService).continueDecision(org.mockito.Mockito.eq(100L), cancellation.capture());
        assertEquals("END_DECISION", cancellation.getValue().getSelectedOptionId());
        verifyNoInteractions(aiClient);
    }
    @Test
    void demandSwitchVocabularyExcludesShopInquiry() {
        // #34 词表级反例疫苗：新增「有没有别的/别的吃的/换点别的」命中换需求，
        // 但聚焦商户的「评价/优惠」是 ShopInquiry（BUSINESS_FOLLOW_UP）而非换需求，必须排除。
        ChatOrchestrationService service = new ChatOrchestrationService();
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "isAlternativeRecommendationPhrase", "看看有没有别的吃的"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "isAlternativeRecommendationPhrase", "有没有别的菜"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "isAlternativeRecommendationPhrase", "换点别的"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "isAlternativeRecommendationPhrase", "还有别的"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "isAlternativeRecommendationPhrase", "再推荐几家"));
        // 反例：ShopInquiry 强词存在时不判换需求
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "isAlternativeRecommendationPhrase", "这家店有没有别的优惠"));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "isAlternativeRecommendationPhrase", "这家店还有没有别的评价"));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "isAlternativeRecommendationPhrase", "找家评价好的店"));
    }

    @Test
    void completedStateDemandSwitchStartsNewDecision() {
        // #34 端到端：已有推荐（COMPLETED）后「看看有没有别的吃的」→ START_DECISION 重决策，
        // 而非落入 LLM 被误判 BUSINESS_FOLLOW_UP 追问候选池。
        ChatOrchestrationService service = new ChatOrchestrationService();
        OpenAiCompatibleClient aiClient = mock(OpenAiCompatibleClient.class);
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        ConversationStateService stateService = mock(ConversationStateService.class);
        AgentConversationService conversationService = mock(AgentConversationService.class);
        AmapMcpLocationResolutionService locationResolutionService = mock(AmapMcpLocationResolutionService.class);
        ReflectionTestUtils.setField(service, "aiClient", aiClient);
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        ReflectionTestUtils.setField(service, "conversationService", conversationService);
        ReflectionTestUtils.setField(service, "chatMemoryService", memoryService);
        ReflectionTestUtils.setField(service, "conversationStateService", stateService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "locationResolutionService", locationResolutionService);
        when(memoryService.resolveChatId(any())).thenReturn("test-chat");
        when(memoryService.load("test-chat")).thenReturn(Collections.emptyList());
        AiChatSession state = new AiChatSession();
        state.setChatId("test-chat");
        state.setActiveDecisionSessionId(100L);
        when(stateService.getOrCreate("test-chat")).thenReturn(state);
        DecisionResponse completed = new DecisionResponse();
        completed.setSessionId(100L);
        completed.setStatus("COMPLETED");
        when(decisionService.getDecision(100L)).thenReturn(completed);
        DecisionResponse cancelled = new DecisionResponse();
        cancelled.setSessionId(100L);
        cancelled.setStatus("CANCELLED");
        when(decisionService.continueDecision(org.mockito.Mockito.eq(100L), any())).thenReturn(cancelled);
        DecisionResponse started = new DecisionResponse();
        started.setSessionId(101L);
        started.setStatus("COMPLETED");
        when(decisionService.decide(any())).thenReturn(started);

        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("看看有没有别的吃的");
        ChatMessageResponse response = service.chat(request);

        assertEquals("START_DECISION", response.getRoute());
        verifyNoInteractions(aiClient);
    }
    @Test
    void vocabCatchesModelFallbackScenarios() {
        // 审计 run78 落 MODEL 的 turn（2026-09-03）：高频确定性表述由规则接住，避免每轮 LLM 兜底。
        // 正例：本应规则接住的表述
        ChatOrchestrationService service = new ChatOrchestrationService();
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "isNewRecommendationIntent", "附近有什么好吃的闽菜"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "isNewRecommendationIntent", "我在杭州想吃火锅"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "isNewRecommendationIntent", "推荐一家杭州适合约会的日料"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "isNewRecommendationIntent", "附近的火锅"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "isNewRecommendationIntent", "我想吃日料"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "isShopInquiry", "推荐的这几家走过去大概多远"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "isShopInquiry", "那另一家店有打折券吗"));
        // 反例：#29 领域守卫与无餐饮语义不回归
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "isNewRecommendationIntent", "附近有什么好玩的"));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "isNewRecommendationIntent", "帮我看看附近有没有羽毛球馆"));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "isNewRecommendationIntent", "附近有什么推荐"));
    }
    @Test
    void critiqueWordsDoNotSwallowShopInquiry() {
        // GLM 碰撞疫苗（2026-09-04）：critique 词表新增「实惠/平价/好贵」后，
        // 聚焦商户的问句「这家店实惠吗」必须走 BUSINESS_FOLLOW_UP（isFocusedShopQuestion 短路 + isShopInquiry），
        // 不被 isSearchRefinement 的「实惠」子串吞成 START_DECISION 误收紧预算。
        ChatOrchestrationService service = new ChatOrchestrationService();
        // 聚焦信号存在 → isNewRecommendationIntent 短路 false
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "isNewRecommendationIntent", "这家店实惠吗"));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "isNewRecommendationIntent", "这里平价吗"));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "isNewRecommendationIntent", "这家店贵吗"));
        // 聚焦信号 + ShopInquiry 强词
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "isFocusedShopQuestion", "这家店实惠吗"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "isShopInquiry", "这家店实惠吗"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "isShopInquiry", "这家店贵吗"));
    }

    @Test
    void shopInquiryWithYouShenMeNotSwallowedByStartDecision() {
        // GLM 碰撞检查："有什么"进入 isNewRecommendationIntent 后，
        // "这家店有什么优惠"必须走 BUSINESS_FOLLOW_UP（isFocusedShopQuestion 兜底 + isShopInquiry），
        // 不被正向规则 START_DECISION 吞掉（isShopInquiry 判定晚于正向规则）。
        ChatOrchestrationService service = new ChatOrchestrationService();
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "isNewRecommendationIntent", "这家店有什么优惠"));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "isNewRecommendationIntent", "这家店有什么评价"));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "isNewRecommendationIntent", "有什么优惠"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "isShopInquiry", "这家店有什么优惠"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "isFocusedShopQuestion", "这家店有什么优惠"));
    }
}