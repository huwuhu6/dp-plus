package com.hmdp.ai.service;

import com.hmdp.ai.client.SpringAiTextClient;
import com.hmdp.ai.config.AiProperties;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConversationContextRewriterTest {
    @Test
    void rewritesOrdinalFollowUpWithWorkingMemory() {
        ConversationContextRewriter rewriter = new ConversationContextRewriter();
        SpringAiTextClient textClient = mock(SpringAiTextClient.class);
        AiProperties properties = new AiProperties();
        properties.setApiKey("test-key");
        ReflectionTestUtils.setField(rewriter, "springAiTextClient", textClient);
        ReflectionTestUtils.setField(rewriter, "aiProperties", properties);
        when(textClient.chatText(any(), eq("CONTEXT_REWRITE")))
                .thenReturn("查询闽师东北菜（上街大学城店）当前可用的团购和优惠券");

        ContextRewriteResult result = rewriter.rewrite("第一家有优惠券吗？", Collections.emptyList(), workingMemory());

        assertTrue(result.getApplied());
        assertTrue(result.getUsedModel());
        assertEquals("第一家有优惠券吗？", result.getOriginalQuery());
        assertEquals("查询闽师东北菜（上街大学城店）当前可用的团购和优惠券", result.getRewrittenQuery());
        verify(textClient).chatText(any(), eq("CONTEXT_REWRITE"));
    }

    @Test
    void keepsSelfContainedQueryOutOfModelRewrite() {
        ConversationContextRewriter rewriter = new ConversationContextRewriter();
        SpringAiTextClient textClient = mock(SpringAiTextClient.class);
        AiProperties properties = new AiProperties();
        properties.setApiKey("test-key");
        ReflectionTestUtils.setField(rewriter, "springAiTextClient", textClient);
        ReflectionTestUtils.setField(rewriter, "aiProperties", properties);

        ContextRewriteResult result = rewriter.rewrite("推荐福州鼓楼区适合约会的日料", Collections.emptyList(), workingMemory());

        assertFalse(result.getApplied());
        assertEquals("SELF_CONTAINED", result.getReason());
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
        context.setShownShops(Arrays.asList(first));
        return context;
    }
}
