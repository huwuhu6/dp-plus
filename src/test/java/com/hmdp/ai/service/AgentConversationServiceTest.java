package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.AgentConversationRequest;
import com.hmdp.ai.dto.AgentConversationResponse;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.entity.AiAgentToolCall;
import com.hmdp.ai.entity.AiDecisionSession;
import com.hmdp.ai.mapper.AiAgentToolCallMapper;
import com.hmdp.ai.mapper.AiDecisionMessageMapper;
import com.hmdp.ai.mapper.AiDecisionSessionMapper;
import com.hmdp.ai.tool.AgentToolRegistry;
import com.hmdp.ai.tool.AgentToolResult;
import com.hmdp.ai.tool.BaseAgentTool;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

class AgentConversationServiceTest {
    @Test
    void routesVoucherQuestionToReadOnlyToolWhenModelIsUnavailable() throws Exception {
        AgentConversationService service = new AgentConversationService();
        AiDecisionSessionMapper sessionMapper = mock(AiDecisionSessionMapper.class);
        AiDecisionMessageMapper messageMapper = mock(AiDecisionMessageMapper.class);
        AiAgentToolCallMapper toolCallMapper = mock(AiAgentToolCallMapper.class);
        AgentToolRegistry registry = mock(AgentToolRegistry.class);
        BaseAgentTool voucherTool = mock(BaseAgentTool.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AiProperties properties = new AiProperties();
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(service, "toolRegistry", registry);
        ReflectionTestUtils.setField(service, "aiClient", mock(OpenAiCompatibleClient.class));
        ReflectionTestUtils.setField(service, "aiProperties", properties);
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);

        AiDecisionSession session = completedSession(objectMapper);
        when(sessionMapper.selectById(100L)).thenReturn(session);
        when(registry.find("query_shop_vouchers")).thenReturn(voucherTool);
        AgentToolResult toolResult = new AgentToolResult().summary("查询到 1 张上架优惠券").displayText("当前可用优惠券：50 元代金券");
        when(voucherTool.execute(anyMap(), any())).thenReturn(toolResult);

        AgentConversationRequest request = new AgentConversationRequest();
        request.setMessage("这一家有什么优惠券？");
        AgentConversationResponse response = service.converse(100L, request);

        assertFalse(response.getUsedModel());
        assertEquals("当前可用优惠券：50 元代金券", response.getAnswer());
        assertEquals("query_shop_vouchers", response.getToolTrace().get(0).getToolName());
        assertEquals(Long.valueOf(8L), response.getFocusedShopId());
        ArgumentCaptor<AiAgentToolCall> callCaptor = ArgumentCaptor.forClass(AiAgentToolCall.class);
        verify(toolCallMapper).insert(callCaptor.capture());
        assertEquals("SUCCESS", callCaptor.getValue().getStatus());
        verify(sessionMapper).updateById(session);
    }

    @Test
    void answersCapabilityQuestionWithoutCallingBusinessTool() throws Exception {
        AgentConversationService service = new AgentConversationService();
        AiDecisionSessionMapper sessionMapper = mock(AiDecisionSessionMapper.class);
        AiDecisionMessageMapper messageMapper = mock(AiDecisionMessageMapper.class);
        AiAgentToolCallMapper toolCallMapper = mock(AiAgentToolCallMapper.class);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(service, "toolRegistry", mock(AgentToolRegistry.class));
        ReflectionTestUtils.setField(service, "aiClient", mock(OpenAiCompatibleClient.class));
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        ObjectMapper objectMapper = new ObjectMapper();
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
        when(sessionMapper.selectById(100L)).thenReturn(completedSession(objectMapper));
        AgentConversationRequest request = new AgentConversationRequest();
        request.setMessage("你是谁？");

        AgentConversationResponse response = service.converse(100L, request);

        assertTrue(response.getAnswer().contains("点评消费决策助手"));
        assertTrue(response.getToolTrace().isEmpty());
        verify(toolCallMapper, never()).insert(any(AiAgentToolCall.class));
        verify(sessionMapper, never()).updateById(any(AiDecisionSession.class));
    }

    private AiDecisionSession completedSession(ObjectMapper objectMapper) throws Exception {
        DecisionRecommendation recommendation = new DecisionRecommendation();
        recommendation.setShopId(8L);
        recommendation.setShopName("测试寿司店");
        DecisionResponse response = new DecisionResponse();
        response.setStatus("COMPLETED");
        response.getRecommendations().add(recommendation);
        AiDecisionSession session = new AiDecisionSession();
        session.setId(100L);
        session.setStatus("COMPLETED");
        session.setResultJson(objectMapper.writeValueAsString(response));
        return session;
    }
}
