package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.AgentConversationResponse;
import com.hmdp.ai.dto.ChatMessageRequest;
import com.hmdp.ai.dto.ChatMessageResponse;
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
        ChatSessionStateService sessionStateService = mock(ChatSessionStateService.class);
        ReflectionTestUtils.setField(service, "aiClient", mock(OpenAiCompatibleClient.class));
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        ReflectionTestUtils.setField(service, "conversationService", conversationService);
        ReflectionTestUtils.setField(service, "chatMemoryService", memoryService);
        ReflectionTestUtils.setField(service, "chatSessionStateService", sessionStateService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        when(memoryService.resolveChatId(any())).thenReturn("test-chat");
        when(memoryService.load("test-chat")).thenReturn(Collections.emptyList());
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
        ChatSessionStateService sessionStateService = mock(ChatSessionStateService.class);
        AiProperties properties = new AiProperties();
        properties.setApiKey("test-key");
        ReflectionTestUtils.setField(service, "aiClient", aiClient);
        ReflectionTestUtils.setField(service, "aiProperties", properties);
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        ReflectionTestUtils.setField(service, "conversationService", conversationService);
        ReflectionTestUtils.setField(service, "chatMemoryService", memoryService);
        ReflectionTestUtils.setField(service, "chatSessionStateService", sessionStateService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        when(memoryService.resolveChatId(any())).thenReturn("test-chat");
        when(memoryService.load("test-chat")).thenReturn(Collections.emptyList());
        AiChatSession state = new AiChatSession();
        state.setChatId("test-chat");
        state.setLastDecisionSessionId(36L);
        when(sessionStateService.get("test-chat")).thenReturn(state);
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
        verify(sessionStateService).rememberLast("test-chat", 36L);
    }
}
