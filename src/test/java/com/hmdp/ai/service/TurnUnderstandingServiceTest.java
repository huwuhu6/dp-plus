package com.hmdp.ai.service;

import com.hmdp.ai.dto.ContextRewriteResult;
import com.hmdp.ai.dto.DecisionContextQuery;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.ReferenceIntent;
import com.hmdp.ai.dto.ResolvedShopReference;
import com.hmdp.ai.dto.TurnCommandSet;
import com.hmdp.ai.dto.TurnCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings = {"这家有没有包厢？", "这家有什么推荐菜？", "这家人均多少？",
            "第二家预算多少？", "这个日料怎么样？", "这家日料评价如何？"})
    void treatsReferenceFactQuestionsAsReadOnly(String message) {
        ContextRewriteResult rewrite = ContextRewriteResult.unchanged(message, "BATCH_REFERENCE_RESOLVED");
        rewrite.setResolvedReferences(Collections.singletonList(new ResolvedShopReference(
                new ReferenceIntent(ReferenceIntent.Scope.FOCUSED, null, "这家", 0, 2), null, 1, 558L, "店铺")));

        TurnCommandSet result = service.understand(message, message, Collections.emptyList(), rewrite);

        assertFalse(result.isMutationRequested(), message);
        assertTrue(result.isReferenceOnly(), message);
        assertFalse(service.shouldApplyReferenceMutation(result), message);
    }

    @Test
    void acceptsStructuredMutationAnchorForResolvedReference() {
        ReferenceIntent intent = new ReferenceIntent(ReferenceIntent.Scope.LATEST, 1, "第一家", 0, 3);
        intent.setMutationAnchor(true);
        ContextRewriteResult rewrite = ContextRewriteResult.unchanged("第一家太贵了，第二家有插座吗？", "BATCH_REFERENCE_RESOLVED");
        rewrite.setResolvedReferences(Collections.singletonList(new ResolvedShopReference(
                intent, null, 1, 558L, "店铺")));

        TurnCommandSet result = service.understand("第一家怎么样，第二家有插座吗？", "第一家怎么样，第二家有插座吗？",
                Collections.emptyList(), rewrite);

        assertTrue(result.isMutationRequested());
        assertFalse(result.isReferenceOnly());
    }

    @Test
    void doesNotUseStaleAssistantPromptForAllFollowUp() {
        TurnCommandSet result = service.understand("所有", "所有", java.util.List.of(
                java.util.Map.of("role", "assistant", "content", "请告诉我你想核对预算、距离或哪一项偏好"),
                java.util.Map.of("role", "user", "content", "换个话题"),
                java.util.Map.of("role", "assistant", "content", "好的，我们可以聊点别的")), null);

        assertFalse(result.isContextQueryRequested());
    }

    @Test
    void recognizesBroadFoodRecoveryOnlyForWaitingRelaxationWithSpecificTarget() {
        DecisionConstraints paused = new DecisionConstraints();
        paused.setNearby(true);
        paused.setRadiusKm(5D);
        paused.setKeyword("兰州拉面");
        paused.setCuisine("面食");

        TurnCommandSet result = service.understand("那附近有啥", "那附近有啥", Collections.emptyList(), null,
                "WAITING_RELAXATION", paused);

        assertTrue(result.hasCommand(TurnCommand.Type.BROADEN_FOOD_SCOPE));
    }

    @Test
    void broadFoodRecoveryDoesNotApplyToOrdinaryConversationOrExplanation() {
        DecisionConstraints paused = new DecisionConstraints();
        paused.setNearby(true);
        paused.setKeyword("兰州拉面");
        paused.setCuisine("面食");

        assertFalse(service.understand("附近有啥", "附近有啥", Collections.emptyList(), null,
                "COMPLETED", paused).hasCommand(TurnCommand.Type.BROADEN_FOOD_SCOPE));
        assertFalse(service.understand("什么意思", "什么意思", Collections.emptyList(), null,
                "WAITING_RELAXATION", paused).hasCommand(TurnCommand.Type.BROADEN_FOOD_SCOPE));
        assertFalse(service.understand("没有兰州拉面吗", "没有兰州拉面吗", Collections.emptyList(), null,
                "WAITING_RELAXATION", paused).hasCommand(TurnCommand.Type.BROADEN_FOOD_SCOPE));
    }

    @Test
    void broadFoodRecoveryDoesNotTreatSpecificTargetBrowseAsRelaxation() {
        DecisionConstraints paused = new DecisionConstraints();
        paused.setNearby(true);
        paused.setKeyword("兰州拉面");
        paused.setCuisine("面食");

        TurnCommandSet sameTarget = service.understand("附近有什么兰州拉面？", "附近有什么兰州拉面？",
                Collections.emptyList(), null, "WAITING_RELAXATION", paused);
        TurnCommandSet keepTarget = service.understand("还是找兰州拉面吧", "还是找兰州拉面吧",
                Collections.emptyList(), null, "WAITING_RELAXATION", paused);
        TurnCommandSet abandonTarget = service.understand("不一定要兰州拉面，附近吃啥都行", "不一定要兰州拉面，附近吃啥都行",
                Collections.emptyList(), null, "WAITING_RELAXATION", paused);

        assertFalse(sameTarget.hasCommand(TurnCommand.Type.BROADEN_FOOD_SCOPE));
        assertFalse(keepTarget.hasCommand(TurnCommand.Type.BROADEN_FOOD_SCOPE));
        assertTrue(abandonTarget.hasCommand(TurnCommand.Type.BROADEN_FOOD_SCOPE));
    }

    @Test
    void broadFoodRecoveryIgnoresBlankPausedFoodTargetsWhenCheckingRepetition() {
        DecisionConstraints cuisineOnly = new DecisionConstraints();
        cuisineOnly.setNearby(true);
        cuisineOnly.setCuisine("火锅");
        DecisionConstraints keywordOnly = new DecisionConstraints();
        keywordOnly.setNearby(true);
        keywordOnly.setKeyword("兰州拉面");

        assertTrue(service.understand("那附近有啥", "那附近有啥", Collections.emptyList(), null,
                "WAITING_RELAXATION", cuisineOnly).hasCommand(TurnCommand.Type.BROADEN_FOOD_SCOPE));
        assertTrue(service.understand("那附近有啥", "那附近有啥", Collections.emptyList(), null,
                "WAITING_RELAXATION", keywordOnly).hasCommand(TurnCommand.Type.BROADEN_FOOD_SCOPE));
        assertFalse(service.understand("附近有什么火锅", "附近有什么火锅", Collections.emptyList(), null,
                "WAITING_RELAXATION", cuisineOnly).hasCommand(TurnCommand.Type.BROADEN_FOOD_SCOPE));
        assertFalse(service.understand("附近有什么兰州拉面", "附近有什么兰州拉面", Collections.emptyList(), null,
                "WAITING_RELAXATION", keywordOnly).hasCommand(TurnCommand.Type.BROADEN_FOOD_SCOPE));
    }
}
