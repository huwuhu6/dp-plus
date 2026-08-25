package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.client.SpringAiTextClient;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.AgentConversationResponse;
import com.hmdp.ai.dto.ChatLocationInput;
import com.hmdp.ai.dto.ChatMessageRequest;
import com.hmdp.ai.dto.ChatMessageResponse;
import com.hmdp.ai.dto.ConversationLocationSlot;
import com.hmdp.ai.dto.ConversationSlots;
import com.hmdp.ai.dto.DecisionFollowUpRequest;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatOrchestrationServiceTest {
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
        context.getShownShops().add(candidate);
        context.getShownShopIds().add(9L);
        when(stateService.agentContext(state)).thenReturn(context);
        com.hmdp.ai.dto.ContextRewriteResult rewrite = new com.hmdp.ai.dto.ContextRewriteResult();
        rewrite.setOriginalQuery("第二家怎么样？");
        rewrite.setRewrittenQuery("筑地日本料理（上街店）怎么样？");
        rewrite.setApplied(true);
        rewrite.setUsedModel(true);
        rewrite.setReason("ELLIPSIS_RESOLVED");
        when(rewriter.rewrite(any(), any(), any())).thenReturn(rewrite);
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
    @org.junit.jupiter.api.Disabled("Explicit destinations are now asserted through NLU slots and state reduction, not Gateway regex extraction.")
    void explicitPlaceSkipsPreviousBrowserLocationAndRequestsMapConfirmation() {
        ChatOrchestrationService service = new ChatOrchestrationService();
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        ConversationStateService stateService = mock(ConversationStateService.class);
        AmapMcpLocationResolutionService locationService = mock(AmapMcpLocationResolutionService.class);
        ReflectionTestUtils.setField(service, "aiClient", mock(OpenAiCompatibleClient.class));
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        ReflectionTestUtils.setField(service, "conversationService", mock(AgentConversationService.class));
        ReflectionTestUtils.setField(service, "chatMemoryService", memoryService);
        ReflectionTestUtils.setField(service, "conversationStateService", stateService);
        ReflectionTestUtils.setField(service, "locationResolutionService", locationService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        when(memoryService.resolveChatId(any())).thenReturn("test-chat");
        when(memoryService.load("test-chat")).thenReturn(Collections.emptyList());
        AiChatSession state = new AiChatSession();
        state.setChatId("test-chat");
        when(stateService.getOrCreate("test-chat")).thenReturn(state);
        when(locationService.isAvailable()).thenReturn(true);
        DecisionResponse decision = new DecisionResponse();
        decision.setSessionId(57L);
        decision.setStatus("CLARIFYING");
        decision.setOptions(new java.util.ArrayList<>());
        when(decisionService.decide(any())).thenReturn(decision);
        ResolvedLocationCandidate candidate = new ResolvedLocationCandidate();
        candidate.setLabel("重庆市");
        candidate.setLatitude(29.563D);
        candidate.setLongitude(106.551D);
        when(locationService.resolve("重庆")).thenReturn(Arrays.asList(candidate));

        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("帮我找重庆附近的火锅");
        ChatMessageResponse response = service.chat(request);

        ArgumentCaptor<DecisionRequest> decisionRequest = ArgumentCaptor.forClass(DecisionRequest.class);
        verify(decisionService).decide(decisionRequest.capture());
        assertEquals(null, decisionRequest.getValue().getLatitude());
        assertEquals("LOCATION_RESOLUTION", response.getRoute());
        assertEquals(57L, response.getDecisionSessionId());
        verify(locationService).resolve("重庆");
    }

    @Test
    @org.junit.jupiter.api.Disabled("Gateway location regex extraction was removed in favor of the structured NLU contract.")
    void nearbySearchPhraseDoesNotTreatActionWordsAsAnExplicitLocation() {
        ChatOrchestrationService service = new ChatOrchestrationService();
        AmapMcpLocationResolutionService locationService = mock(AmapMcpLocationResolutionService.class);
        ReflectionTestUtils.setField(service, "locationResolutionService", locationService);
        when(locationService.isAvailable()).thenReturn(true);

        assertNull(ReflectionTestUtils.invokeMethod(service, "extractExplicitLocationScope", "帮我找附近的日料"));
        assertNull(ReflectionTestUtils.invokeMethod(service, "extractExplicitLocationScope", "推荐一家附近的日料店"));
        assertEquals("重庆", ReflectionTestUtils.invokeMethod(service, "extractExplicitLocationScope", "帮我找重庆附近的火锅"));
        assertEquals("重庆", ReflectionTestUtils.invokeMethod(service, "extractExplicitLocationScope", "帮我看看重庆有没有啥好吃的，我后面想去"));
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
    @org.junit.jupiter.api.Disabled("Explicit destinations now execute through city/area SQL scope rather than map-confirmation fallback.")
    void explicitPlaceStillBlocksDeviceLocationWhenMapResolutionIsUnavailable() {
        ChatOrchestrationService service = new ChatOrchestrationService();
        OpenAiCompatibleClient aiClient = mock(OpenAiCompatibleClient.class);
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        ConversationStateService stateService = mock(ConversationStateService.class);
        AmapMcpLocationResolutionService locationService = mock(AmapMcpLocationResolutionService.class);
        ReflectionTestUtils.setField(service, "aiClient", aiClient);
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        ReflectionTestUtils.setField(service, "conversationService", mock(AgentConversationService.class));
        ReflectionTestUtils.setField(service, "chatMemoryService", memoryService);
        ReflectionTestUtils.setField(service, "conversationStateService", stateService);
        ReflectionTestUtils.setField(service, "locationResolutionService", locationService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        when(memoryService.resolveChatId(any())).thenReturn("test-chat");
        when(memoryService.load("test-chat")).thenReturn(Collections.emptyList());
        AiChatSession state = new AiChatSession();
        state.setChatId("test-chat");
        when(stateService.getOrCreate("test-chat")).thenReturn(state);
        when(locationService.isAvailable()).thenReturn(false);
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
        assertEquals("LOCATION_RESOLUTION", response.getRoute());
        assertTrue(response.getAnswer().contains("地图解析服务不可用"));
        verify(locationService, never()).resolve(anyString());
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
}
