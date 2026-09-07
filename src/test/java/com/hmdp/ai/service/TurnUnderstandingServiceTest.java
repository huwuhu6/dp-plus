package com.hmdp.ai.service;

import com.hmdp.ai.dto.ContextRewriteResult;
import com.hmdp.ai.dto.DecisionContextQuery;
import com.hmdp.ai.dto.ReferenceIntent;
import com.hmdp.ai.dto.ResolvedShopReference;
import com.hmdp.ai.dto.TurnCommandSet;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnUnderstandingServiceTest {
    private final TurnUnderstandingService service = new TurnUnderstandingService();

    @Test
    void treatsCuisineMentionInResolvedShopQuestionAsReferenceOnly() {
        ContextRewriteResult rewrite = ContextRewriteResult.unchanged("这个日料咋样", "BATCH_REFERENCE_RESOLVED");
        rewrite.setResolvedReferences(Collections.singletonList(new ResolvedShopReference(
                new ReferenceIntent(ReferenceIntent.Scope.FOCUSED, 1, "这个", 0, 2), null, 1, 558L, "日料店")));

        TurnCommandSet result = service.understand("这个日料咋样", "日料店咋样", Collections.emptyList(), rewrite);

        assertTrue(result.isReferenceOnly());
        assertFalse(result.isMutationRequested());
        assertFalse(result.isContextQueryRequested());
    }

    @Test
    void preservesCompoundMutationAsIndependentTurnCommand() {
        TurnCommandSet result = service.understand("第一家太贵了，第二家有插座吗？", "第一家太贵了，第二家有插座吗？",
                Collections.emptyList(), ContextRewriteResult.unchanged("第一家太贵了，第二家有插座吗？", "NO_REWRITE"));

        assertTrue(result.isMutationRequested());
        assertTrue(service.shouldApplyReferenceMutation(result));
    }

    @Test
    void recognizesAllCriteriaQueryAndFollowUp() {
        TurnCommandSet direct = service.understand("你的过滤条件是什么", "你的过滤条件是什么", Collections.emptyList(), null);
        assertEquals(DecisionContextQuery.QueryType.CURRENT_CRITERIA, direct.getContextQuery().getType());

        TurnCommandSet followUp = service.understand("所有", "所有",
                Collections.singletonList(java.util.Map.of("role", "assistant", "content", "请告诉我你想核对预算、距离或哪一项偏好")), null);
        assertEquals(DecisionContextQuery.QueryType.CURRENT_CRITERIA, followUp.getContextQuery().getType());
    }
}
