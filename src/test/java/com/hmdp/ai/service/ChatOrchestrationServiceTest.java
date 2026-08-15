package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.ChatMessageRequest;
import com.hmdp.ai.dto.ChatMessageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ChatOrchestrationServiceTest {
    @Test
    void keepsGeneralChatOutsideDecisionWorkflowWhenModelIsUnavailable() {
        ChatOrchestrationService service = new ChatOrchestrationService();
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        AgentConversationService conversationService = mock(AgentConversationService.class);
        ReflectionTestUtils.setField(service, "aiClient", mock(OpenAiCompatibleClient.class));
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        ReflectionTestUtils.setField(service, "conversationService", conversationService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("hello 啊");

        ChatMessageResponse response = service.chat(request);

        assertEquals("GENERAL_CHAT", response.getRoute());
        assertTrue(response.getAnswer().contains("消费决策助手"));
        verifyNoInteractions(decisionService, conversationService);
    }
}
