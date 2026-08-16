package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.AgentConversationResponse;
import com.hmdp.ai.dto.ChatLocationInput;
import com.hmdp.ai.dto.ChatMessageRequest;
import com.hmdp.ai.dto.ChatMessageResponse;
import com.hmdp.ai.dto.ConversationLocationSlot;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.entity.AiChatSession;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatOrchestrationServiceTest {
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
        when(conversationService.converse(any(), any())).thenReturn(conversation);
        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("那一家评价如何");

        ChatMessageResponse response = service.chat(request);

        assertEquals("BUSINESS_FOLLOW_UP", response.getRoute());
        assertEquals(36L, response.getDecisionSessionId());
        assertEquals("COMPLETED", response.getDecisionStatus());
        verify(conversationService).converse(any(), any());
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
        when(conversationService.hasCandidateReference(36L, "这个日本料理怎么样")).thenReturn(true);
        AgentConversationResponse conversation = new AgentConversationResponse();
        conversation.setAnswer("筑地日本料理（上街店）的评价如下。");
        when(conversationService.converse(any(), any())).thenReturn(conversation);

        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("这个日本料理怎么样");
        ChatMessageResponse response = service.chat(request);

        assertEquals("BUSINESS_FOLLOW_UP", response.getRoute());
        assertEquals("筑地日本料理（上街店）的评价如下。", response.getAnswer());
        verify(conversationService).converse(any(), any());
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
}
