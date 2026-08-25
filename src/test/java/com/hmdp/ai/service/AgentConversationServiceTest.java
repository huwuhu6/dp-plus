package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.AgentConversationRequest;
import com.hmdp.ai.dto.AgentConversationResponse;
import com.hmdp.ai.dto.AgentSessionContext;
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
import com.hmdp.ai.tool.ToolResultCompressor;
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
        ReflectionTestUtils.setField(service, "toolResultCompressor", compressor(objectMapper));

        AiDecisionSession session = completedSession(objectMapper);
        when(sessionMapper.selectById(100L)).thenReturn(session);
        when(registry.find("query_shop_vouchers")).thenReturn(voucherTool);
        AgentToolResult toolResult = new AgentToolResult().summary("查询到 1 张上架优惠券").displayText("当前可用优惠券：50 元代金券");
        when(voucherTool.execute(anyMap())).thenReturn(toolResult);

        AgentConversationRequest request = new AgentConversationRequest();
        request.setMessage("这一家有什么优惠券？");
        AgentConversationResponse response = service.converse(100L, request, singleShopContext());

        assertFalse(response.getUsedModel());
        assertEquals("当前可用优惠券：50 元代金券", response.getAnswer());
        assertEquals("query_shop_vouchers", response.getToolTrace().get(0).getToolName());
        assertEquals(Long.valueOf(8L), response.getFocusedShopId());
        ArgumentCaptor<AiAgentToolCall> callCaptor = ArgumentCaptor.forClass(AiAgentToolCall.class);
        verify(toolCallMapper).insert(callCaptor.capture());
        assertEquals("SUCCESS", callCaptor.getValue().getStatus());
    }

    @Test
    void resolvesNamedCandidateInsteadOfDefaultingToFirstShop() throws Exception {
        AgentConversationService service = new AgentConversationService();
        AiDecisionSessionMapper sessionMapper = mock(AiDecisionSessionMapper.class);
        AiDecisionMessageMapper messageMapper = mock(AiDecisionMessageMapper.class);
        AiAgentToolCallMapper toolCallMapper = mock(AiAgentToolCallMapper.class);
        AgentToolRegistry registry = mock(AgentToolRegistry.class);
        BaseAgentTool evidenceTool = mock(BaseAgentTool.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(service, "toolRegistry", registry);
        ReflectionTestUtils.setField(service, "aiClient", mock(OpenAiCompatibleClient.class));
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(service, "toolResultCompressor", compressor(objectMapper));
        AiDecisionSession session = completedSessionWithJapaneseRestaurant(objectMapper);
        when(sessionMapper.selectById(100L)).thenReturn(session);
        when(registry.find("search_shop_evidence")).thenReturn(evidenceTool);
        when(evidenceTool.execute(anyMap())).thenAnswer(invocation -> {
            assertEquals(9L, ((Number) invocation.getArgument(0, java.util.Map.class).get("shopId")).longValue());
            return new AgentToolResult().summary("检索到评价").displayText("筑地日本料理的本地评价证据");
        });
        AgentConversationRequest request = new AgentConversationRequest();
        request.setMessage("筑地日本料理（上街店）评价如何");

        AgentConversationResponse response = service.converse(100L, request, japaneseContext());

        assertEquals(Long.valueOf(9L), response.getFocusedShopId());
        assertEquals("筑地日本料理（上街店）", response.getFocusedShopName());
        assertEquals("search_shop_evidence", response.getToolTrace().get(0).getToolName());
    }

    @Test
    void fallsBackToBothIndependentFactToolsForCompoundQuestion() throws Exception {
        AgentConversationService service = new AgentConversationService();
        AiDecisionSessionMapper sessionMapper = mock(AiDecisionSessionMapper.class);
        AiDecisionMessageMapper messageMapper = mock(AiDecisionMessageMapper.class);
        AiAgentToolCallMapper toolCallMapper = mock(AiAgentToolCallMapper.class);
        AgentToolRegistry registry = mock(AgentToolRegistry.class);
        BaseAgentTool voucherTool = mock(BaseAgentTool.class);
        BaseAgentTool evidenceTool = mock(BaseAgentTool.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(service, "toolRegistry", registry);
        ReflectionTestUtils.setField(service, "aiClient", mock(OpenAiCompatibleClient.class));
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(service, "toolResultCompressor", compressor(objectMapper));
        when(sessionMapper.selectById(100L)).thenReturn(completedSession(objectMapper));
        when(registry.find("query_shop_vouchers")).thenReturn(voucherTool);
        when(registry.find("search_shop_evidence")).thenReturn(evidenceTool);
        when(voucherTool.execute(anyMap())).thenReturn(new AgentToolResult().summary("券").displayText("优惠券"));
        when(evidenceTool.execute(anyMap())).thenReturn(new AgentToolResult().summary("评价").displayText("评价证据"));
        AgentConversationRequest request = new AgentConversationRequest();
        request.setMessage("这家评价如何，还有优惠券吗？");

        AgentConversationResponse response = service.converse(100L, request, singleShopContext());

        assertEquals(2, response.getToolTrace().size());
        assertEquals("query_shop_vouchers", response.getToolTrace().get(0).getToolName());
        assertEquals("search_shop_evidence", response.getToolTrace().get(1).getToolName());
    }

    @Test
    void bindsExplicitMultiShopFactsToDifferentCandidatesInsteadOfAskingForClarification() throws Exception {
        AgentConversationService service = new AgentConversationService();
        AiDecisionSessionMapper sessionMapper = mock(AiDecisionSessionMapper.class);
        AiDecisionMessageMapper messageMapper = mock(AiDecisionMessageMapper.class);
        AiAgentToolCallMapper toolCallMapper = mock(AiAgentToolCallMapper.class);
        AgentToolRegistry registry = mock(AgentToolRegistry.class);
        BaseAgentTool voucherTool = mock(BaseAgentTool.class);
        BaseAgentTool evidenceTool = mock(BaseAgentTool.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(service, "toolRegistry", registry);
        ReflectionTestUtils.setField(service, "aiClient", mock(OpenAiCompatibleClient.class));
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(service, "toolResultCompressor", compressor(objectMapper));
        when(sessionMapper.selectById(100L)).thenReturn(completedSessionWithJapaneseRestaurant(objectMapper));
        when(registry.find("query_shop_vouchers")).thenReturn(voucherTool);
        when(registry.find("search_shop_evidence")).thenReturn(evidenceTool);
        when(voucherTool.execute(anyMap())).thenAnswer(invocation -> {
            assertEquals(8L, ((Number) invocation.getArgument(0, java.util.Map.class).get("shopId")).longValue());
            return new AgentToolResult().summary("voucher").displayText("first-voucher");
        });
        when(evidenceTool.execute(anyMap())).thenAnswer(invocation -> {
            assertEquals(9L, ((Number) invocation.getArgument(0, java.util.Map.class).get("shopId")).longValue());
            return new AgentToolResult().summary("evidence").displayText("second-evidence");
        });

        AgentConversationRequest request = new AgentConversationRequest();
        request.setMessage("\u7b2c\u4e00\u5bb6\u6709\u4f18\u60e0\u5238\u5417\uff1f\u7b2c\u4e8c\u5bb6\u8bc4\u4ef7\u600e\u4e48\u6837\uff1f");
        AgentConversationResponse response = service.converse(100L, request, japaneseContext());

        assertFalse(response.getAnswer().contains("\u54ea\u4e00\u5bb6"));
        assertEquals(2, response.getToolTrace().size());
        assertEquals("query_shop_vouchers", response.getToolTrace().get(0).getToolName());
        assertEquals("search_shop_evidence", response.getToolTrace().get(1).getToolName());
    }

    @Test
    void resolvesOtherCandidateRelativeToFocusedShop() throws Exception {
        AgentConversationService service = new AgentConversationService();

        assertTrue(service.hasCandidateReference("另一家有优惠券吗", japaneseContext()));
    }

    @Test
    void treatsImplicitFactQuestionAsFocusedShopFollowUp() throws Exception {
        AgentConversationService service = new AgentConversationService();

        assertTrue(service.hasCandidateReference("大家评价刺身新鲜吗", japaneseContext()));
    }

    @Test
    void resolvesReferenceFromSuppliedWorkingMemoryProjectionWithoutLoadingDecisionSession() {
        AgentConversationService service = new AgentConversationService();
        com.hmdp.ai.dto.AgentSessionContext context = new com.hmdp.ai.dto.AgentSessionContext();
        DecisionRecommendation recommendation = new DecisionRecommendation();
        recommendation.setShopId(9L);
        recommendation.setShopName("筑地日本料理（上街店）");
        context.getShownShops().add(recommendation);

        assertTrue(service.hasCandidateReference("这家日本料理评价如何", context));
        assertFalse(service.hasCandidateReference("这家日本料理评价如何", new com.hmdp.ai.dto.AgentSessionContext()));
    }

    private ToolResultCompressor compressor(ObjectMapper objectMapper) {
        ToolResultCompressor compressor = new ToolResultCompressor();
        ReflectionTestUtils.setField(compressor, "objectMapper", objectMapper);
        return compressor;
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

    private AiDecisionSession completedSessionWithJapaneseRestaurant(ObjectMapper objectMapper) throws Exception {
        DecisionRecommendation first = new DecisionRecommendation();
        first.setShopId(8L);
        first.setShopName("闽师东北菜（上街大学城店）");
        DecisionRecommendation japanese = new DecisionRecommendation();
        japanese.setShopId(9L);
        japanese.setShopName("筑地日本料理（上街店）");
        DecisionRecommendation anotherJapanese = new DecisionRecommendation();
        anotherJapanese.setShopId(10L);
        anotherJapanese.setShopName("三上日本料理（湖滨店）");
        DecisionResponse response = new DecisionResponse();
        response.setStatus("COMPLETED");
        response.getRecommendations().add(first);
        response.getRecommendations().add(japanese);
        response.getRecommendations().add(anotherJapanese);
        AiDecisionSession session = new AiDecisionSession();
        session.setId(100L);
        session.setStatus("COMPLETED");
        session.setResultJson(objectMapper.writeValueAsString(response));
        return session;
    }

    private AgentSessionContext singleShopContext() {
        AgentSessionContext context = new AgentSessionContext();
        DecisionRecommendation recommendation = new DecisionRecommendation();
        recommendation.setShopId(8L);
        recommendation.setShopName("测试寿司店");
        context.getShownShops().add(recommendation);
        context.getShownShopIds().add(8L);
        context.setFocusedShopId(8L);
        context.setFocusedShopName("测试寿司店");
        return context;
    }

    private AgentSessionContext japaneseContext() {
        AgentSessionContext context = new AgentSessionContext();
        String[] names = {"闽师东北菜（上街大学城店）", "筑地日本料理（上街店）", "三上日本料理（湖滨店）"};
        for (int index = 0; index < names.length; index++) {
            DecisionRecommendation recommendation = new DecisionRecommendation();
            recommendation.setShopId(8L + index);
            recommendation.setShopName(names[index]);
            context.getShownShops().add(recommendation);
            context.getShownShopIds().add(8L + index);
        }
        context.setFocusedShopId(8L);
        context.setFocusedShopName(names[0]);
        return context;
    }
}
