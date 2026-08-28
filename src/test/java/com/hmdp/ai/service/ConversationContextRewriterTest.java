package com.hmdp.ai.service;

import com.hmdp.ai.client.QueryRewriteClient;
import com.hmdp.ai.dto.AgentSessionContext;
import com.hmdp.ai.dto.ContextRewriteResult;
import com.hmdp.ai.dto.DecisionRecommendation;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConversationContextRewriterTest {
    @Test
    void rewritesOrdinalFollowUpWithWorkingMemory() {
        ConversationContextRewriter rewriter = new ConversationContextRewriter();
        QueryRewriteClient textClient = mock(QueryRewriteClient.class);
        ReflectionTestUtils.setField(rewriter, "queryRewriteClient", textClient);
        when(textClient.isConfigured()).thenReturn(true);
        when(textClient.rewrite(any()))
                .thenReturn("查询闽师东北菜（上街大学城店）当前可用的团购和优惠券");

        ContextRewriteResult result = rewriter.rewrite("第一家有优惠券吗？", Collections.emptyList(), workingMemory());

        assertTrue(result.getApplied());
        assertTrue(result.getUsedModel());
        assertEquals("第一家有优惠券吗？", result.getOriginalQuery());
        assertEquals("查询闽师东北菜（上街大学城店）当前可用的团购和优惠券", result.getRewrittenQuery());
        verify(textClient).rewrite(any());
    }

    @Test
    void keepsSelfContainedQueryOutOfModelRewrite() {
        ConversationContextRewriter rewriter = new ConversationContextRewriter();
        QueryRewriteClient textClient = mock(QueryRewriteClient.class);
        ReflectionTestUtils.setField(rewriter, "queryRewriteClient", textClient);

        ContextRewriteResult result = rewriter.rewrite("推荐福州鼓楼区适合约会的日料", Collections.emptyList(), workingMemory());

        assertFalse(result.getApplied());
        assertEquals("SELF_CONTAINED", result.getReason());
        verifyNoInteractions(textClient);
    }

    @Test
    void rewritesCurrentDeviceContinuationWithoutCandidatePool() {
        ConversationContextRewriter rewriter = new ConversationContextRewriter();
        AgentSessionContext emptyContext = new AgentSessionContext();

        ContextRewriteResult result = rewriter.rewrite("我附近呢", Collections.emptyList(), emptyContext, true);

        assertTrue(result.getApplied());
        assertFalse(result.getUsedModel());
        assertEquals("CURRENT_DEVICE_LOCATION_CONTINUATION", result.getReason());
        assertEquals("在当前设备附近搜索餐饮商户", result.getRewrittenQuery());
    }

    @Test
    void leavesBusinessRefinementClassificationToRouting() {
        ConversationContextRewriter rewriter = new ConversationContextRewriter();
        QueryRewriteClient textClient = mock(QueryRewriteClient.class);
        ReflectionTestUtils.setField(rewriter, "queryRewriteClient", textClient);
        when(textClient.isConfigured()).thenReturn(true);
        when(textClient.rewrite(any())).thenReturn("在当前候选范围中寻找人均更低的备选商户");

        ContextRewriteResult result = rewriter.rewrite("太贵了，有没有便宜点的", Collections.emptyList(), workingMemory());

        assertFalse(result.getApplied());
        assertEquals("SELF_CONTAINED", result.getReason());
        verifyNoInteractions(textClient);
    }

    @Test
    void leavesAlternativeRecommendationToRoutingWithoutRewriteModel() {
        ConversationContextRewriter rewriter = new ConversationContextRewriter();
        QueryRewriteClient textClient = mock(QueryRewriteClient.class);
        ReflectionTestUtils.setField(rewriter, "queryRewriteClient", textClient);

        ContextRewriteResult result = rewriter.rewrite("换几家看看", Collections.emptyList(), workingMemory());

        assertFalse(result.getApplied());
        assertFalse(result.getUsedModel());
        assertEquals("SELF_CONTAINED", result.getReason());
        assertEquals("换几家看看", result.getRewrittenQuery());
        verifyNoInteractions(textClient);
    }

    @Test
    void leavesAlternativeRecommendationWordingToRouting() {
        ConversationContextRewriter rewriter = new ConversationContextRewriter();
        QueryRewriteClient textClient = mock(QueryRewriteClient.class);
        ReflectionTestUtils.setField(rewriter, "queryRewriteClient", textClient);

        for (String query : Arrays.asList("给我再推荐几家不同的", "还有别的餐厅吗", "换一批吧")) {
            ContextRewriteResult result = rewriter.rewrite(query, Collections.emptyList(), workingMemory());
            assertFalse(result.getApplied(), query);
            assertEquals("SELF_CONTAINED", result.getReason(), query);
        }
        verifyNoInteractions(textClient);
    }

    private AgentSessionContext workingMemory() {
        DecisionRecommendation first = new DecisionRecommendation();
        first.setShopId(101L);
        first.setShopName("闽师东北菜（上街大学城店）");
        first.setAvgPrice(65L);
        AgentSessionContext context = new AgentSessionContext();
        context.setFocusedShopId(101L);
        context.setFocusedShopName(first.getShopName());
        context.setCandidatePoolSnapshot(Arrays.asList(first));
        return context;
    }
}
