package com.hmdp.ai.service;

import com.hmdp.ai.dto.CriteriaMergeResult;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.DecisionRecommendation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationCriteriaMergerTest {
    private final ConversationCriteriaMerger merger = new ConversationCriteriaMerger();

    @Test
    void replacesCuisineAndKeepsUnmentionedCriteriaForRefinement() {
        DecisionConstraints previous = new DecisionConstraints();
        previous.setCuisine("川菜");
        previous.setBudgetPerPerson(200);
        previous.setRadiusKm(3D);
        previous.setNearby(true);
        previous.getPreferences().add("聚餐");

        DecisionConstraints delta = new DecisionConstraints();
        delta.setCuisine("粤菜");
        CriteriaMergeResult result = merger.merge(previous, delta, "不要辣的，换成粤菜，重新推荐");

        assertEquals("粤菜", result.getConstraints().getCuisine());
        assertEquals(200, result.getConstraints().getBudgetPerPerson());
        assertEquals(3D, result.getConstraints().getRadiusKm());
        assertTrue(result.getConstraints().getNearby());
        assertTrue(result.getConstraints().getPreferences().contains("清淡"));
        assertTrue(result.getReplaced().stream().anyMatch(item -> item.startsWith("cuisine:")));
        assertFalse(result.getInherited().isEmpty());
    }

    @Test
    void explicitlyClearsOnlyRequestedCriteria() {
        DecisionConstraints previous = new DecisionConstraints();
        previous.setCuisine("川菜");
        previous.setBudgetPerPerson(150);
        previous.setRadiusKm(2D);
        previous.setNearby(true);

        CriteriaMergeResult result = merger.merge(previous, new DecisionConstraints(), "全城都行，预算不限，什么都行");

        assertEquals("", result.getConstraints().getCuisine());
        assertEquals(-1, result.getConstraints().getBudgetPerPerson());
        assertEquals(-1D, result.getConstraints().getRadiusKm());
        assertFalse(result.getConstraints().getNearby());
        assertTrue(result.getCleared().contains("cuisine"));
        assertTrue(result.getCleared().contains("budgetPerPerson"));
    }

    @Test
    void appendsNewLabelsWithoutDuplicatingExistingPreferences() {
        DecisionConstraints previous = new DecisionConstraints();
        previous.getPreferences().add("必吃榜");
        previous.getPreferences().add("安静");
        DecisionConstraints delta = new DecisionConstraints();
        delta.getPreferences().add("必吃榜");
        delta.getPreferences().add("有停车位");
        delta.getPreferences().add("安静");
        delta.getPreferences().add("有包厢");

        CriteriaMergeResult result = merger.merge(previous, delta, "有停车位和包厢吗");

        assertEquals(java.util.Arrays.asList("必吃榜", "安静", "有停车位", "有包厢"), result.getConstraints().getPreferences());
        assertEquals(2, result.getAppended().size());
    }

    @Test
    void overwritesExplicitDestinationAsStructuredLocationRatherThanFreeText() {
        DecisionConstraints previous = new DecisionConstraints();
        previous.setTargetCity("福州");
        previous.setTargetArea("鼓楼区");
        DecisionConstraints delta = new DecisionConstraints();
        delta.setTargetCity("重庆");
        delta.setTargetArea("解放碑");
        delta.setKeyword("火锅");

        CriteriaMergeResult result = merger.merge(previous, delta, "去重庆解放碑吃火锅");

        assertEquals("重庆", result.getConstraints().getTargetCity());
        assertEquals("解放碑", result.getConstraints().getTargetArea());
        assertEquals("火锅", result.getConstraints().getKeyword());
        assertTrue(result.getReplaced().stream().anyMatch(item -> item.startsWith("targetCity:")));
        assertTrue(result.getReplaced().stream().anyMatch(item -> item.startsWith("targetArea:")));
    }

    @Test
    void currentDeviceIntentClearsPriorNamedDestination() {
        DecisionConstraints previous = new DecisionConstraints();
        previous.setTargetCity("北京");
        previous.setTargetArea("朝阳区");
        previous.setLocationIntent("EXPLICIT_TARGET");

        DecisionConstraints delta = new DecisionConstraints();
        delta.setLocationIntent("CURRENT_DEVICE");
        delta.setNearby(true);

        CriteriaMergeResult result = merger.merge(previous, delta, "看看我附近有什么好吃的");

        assertEquals("", result.getConstraints().getTargetCity());
        assertEquals("", result.getConstraints().getTargetArea());
        assertEquals("CURRENT_DEVICE", result.getConstraints().getLocationIntent());
        assertTrue(result.getCleared().contains("targetCity"));
        assertTrue(result.getCleared().contains("targetArea"));
    }

    @Test
    void derivesLowerBudgetFromFocusedCandidateForCheaperRefinement() {
        DecisionRecommendation focused = new DecisionRecommendation();
        focused.setShopId(2L); focused.setAvgPrice(120L);
        DecisionRecommendation lower = new DecisionRecommendation();
        lower.setShopId(3L); lower.setAvgPrice(80L);

        CriteriaMergeResult result = merger.merge(new DecisionConstraints(), new DecisionConstraints(), "太贵了，换个便宜点的",
                java.util.Arrays.asList(focused, lower), 2L, java.util.Arrays.asList(2L));

        // expected migration (design change 2026-09-04): anchorPrice-1 was meaningless for price,
        // replaced by proportional step: anchor 120 x 0.85 = 102 (Math.round)
        assertEquals(102, result.getConstraints().getBudgetPerPerson());
        assertTrue(result.getConstraints().getLockedConstraints().contains("budgetPerPerson"));
        assertTrue(result.getAppended().stream().anyMatch(item -> item.startsWith("relativeBudget:anchorPrice=120")));
    }

    @Test
    void derivesNarrowerRadiusFromFocusedCandidateForCloserRefinement() {
        DecisionRecommendation focused = new DecisionRecommendation();
        focused.setShopId(2L); focused.setDistanceKm(2.5D);

        CriteriaMergeResult result = merger.merge(new DecisionConstraints(), new DecisionConstraints(), "换个更近一点的",
                java.util.Collections.singletonList(focused), 2L, java.util.Arrays.asList(2L));

        assertEquals(2.3D, result.getConstraints().getRadiusKm());
        assertTrue(result.getAppended().stream().anyMatch(item -> item.startsWith("relativeDistance:anchorKm=2.5")));
    }

    @Test
    void explicitBudgetOverrideUnlocksPriorRelativeBudgetLock() {
        DecisionConstraints previous = new DecisionConstraints();
        previous.setBudgetPerPerson(64);
        previous.getLockedConstraints().add("budgetPerPerson");
        DecisionConstraints delta = new DecisionConstraints();
        delta.setBudgetPerPerson(100);

        CriteriaMergeResult result = merger.merge(previous, delta, "\u9884\u7b97 100 \u5143");

        assertEquals(100, result.getConstraints().getBudgetPerPerson());
        assertFalse(result.getConstraints().getLockedConstraints().contains("budgetPerPerson"));
    }

    @Test
    void clearedFieldsFromLlmClearAbandonedCriteria() {
        DecisionConstraints previous = new DecisionConstraints();
        previous.setCuisine("\u6c99\u53bf\u5c0f\u5403");
        previous.setKeyword("\u6c99\u53bf\u5c0f\u5403");
        previous.setRadiusKm(5D);
        DecisionConstraints delta = new DecisionConstraints();
        delta.getClearedFields().add("cuisine");
        delta.getClearedFields().add("keyword");

        CriteriaMergeResult result = merger.merge(previous, delta, "\u770b\u770b\u6709\u6ca1\u6709\u522b\u7684\u5403\u7684");

        assertEquals("", result.getConstraints().getCuisine());
        assertEquals("", result.getConstraints().getKeyword());
        assertEquals(5D, result.getConstraints().getRadiusKm());
        assertTrue(result.getCleared().contains("cuisine"));
        assertTrue(result.getCleared().contains("keyword"));
    }

    @Test
    void ruleFallbackClearsCuisineForOtherFoodsWithoutLlmClearedFields() {
        DecisionConstraints previous = new DecisionConstraints();
        previous.setCuisine("\u6c99\u53bf\u5c0f\u5403");
        previous.setKeyword("\u6c99\u53bf\u5c0f\u5403");
        DecisionConstraints delta = new DecisionConstraints();

        CriteriaMergeResult result = merger.merge(previous, delta, "\u770b\u770b\u6709\u6ca1\u6709\u522b\u7684\u5403\u7684");

        assertEquals("", result.getConstraints().getCuisine());
        assertEquals("", result.getConstraints().getKeyword());
        assertTrue(result.getCleared().contains("cuisine"));
    }

    @Test
    void clearedFieldsOnlyClearWhenPriorConstraintPresent() {
        DecisionConstraints previous = new DecisionConstraints();
        previous.setCuisine("");
        DecisionConstraints delta = new DecisionConstraints();
        delta.getClearedFields().add("cuisine");

        CriteriaMergeResult result = merger.merge(previous, delta, "\u770b\u770b\u6709\u6ca1\u6709\u522b\u7684\u5403\u7684");

        assertEquals("", result.getConstraints().getCuisine());
        assertFalse(result.getCleared().contains("cuisine"));
    }

    @Test
    void llmBudgetDirectionAppliesBudgetWithoutRelianceOnTriggerWords() {
        // R1: "火锅有点贵，有没有稍微平价一点的" — LLM outputs budgetDirection=-1 (no absolute number),
        // so tightening works even though no rule trigger word is present.
        DecisionRecommendation focused = new DecisionRecommendation();
        focused.setShopId(2L); focused.setAvgPrice(120L);
        DecisionConstraints delta = new DecisionConstraints();
        delta.setBudgetDirection(-1);

        CriteriaMergeResult result = merger.merge(new DecisionConstraints(), delta, "火锅有点贵，有没有稍微平价一点的",
                java.util.Arrays.asList(focused), 2L, java.util.Arrays.asList(2L));

        // anchor 120 x 0.85 = 102
        assertEquals(102, result.getConstraints().getBudgetPerPerson());
        assertTrue(result.getConstraints().getLockedConstraints().contains("budgetPerPerson"));
    }

    @Test
    void expandedRuleWordsCatchGoodExpensivePhrasing() {
        // R2: "好贵啊" was missed before — now matched by rule fallback words.
        DecisionRecommendation focused = new DecisionRecommendation();
        focused.setShopId(2L); focused.setAvgPrice(110L);

        CriteriaMergeResult result = merger.merge(new DecisionConstraints(), new DecisionConstraints(), "好贵啊",
                java.util.Arrays.asList(focused), 2L, java.util.Arrays.asList(2L));

        // anchor 110 x 0.85 = 93.5 -> round 94
        assertEquals(94, result.getConstraints().getBudgetPerPerson());
        assertTrue(result.getConstraints().getLockedConstraints().contains("budgetPerPerson"));
    }

    @Test
    void shopComplaintAnchorsOnThatShopAndKeepsOtherConstraints() {
        // R3: "朝天门不错，就是太贵了" — other constraints retained, budget anchored to that shop.
        DecisionConstraints previous = new DecisionConstraints();
        previous.setCuisine("火锅");
        previous.setRadiusKm(3D);
        DecisionRecommendation shop = new DecisionRecommendation();
        shop.setShopId(77L); shop.setAvgPrice(110L);

        CriteriaMergeResult result = merger.merge(previous, new DecisionConstraints(), "朝天门不错，就是太贵了",
                java.util.Arrays.asList(shop), 77L, java.util.Arrays.asList(77L));

        assertEquals("火锅", result.getConstraints().getCuisine());
        assertEquals(3D, result.getConstraints().getRadiusKm());
        assertEquals(94, result.getConstraints().getBudgetPerPerson());
        assertTrue(result.getConstraints().getLockedConstraints().contains("budgetPerPerson"));
    }

    @Test
    void anchorsOnShownSetNotWholePool() {
        // GLM min(池) 反例：用户只见过 110 的店（shown），池里还有一家 85 的从没展示过。
        // 锚必须是 shown 集（110 -> 94），不能锚到池 min（85 -> 72）而误杀没见过的候选。
        DecisionRecommendation shown = new DecisionRecommendation();
        shown.setShopId(2L); shown.setAvgPrice(110L);
        DecisionRecommendation unseen = new DecisionRecommendation();
        unseen.setShopId(3L); unseen.setAvgPrice(85L);

        CriteriaMergeResult result = merger.merge(new DecisionConstraints(), new DecisionConstraints(), "好贵啊",
                java.util.Arrays.asList(shown, unseen), null, java.util.Arrays.asList(2L));

        assertEquals(94, result.getConstraints().getBudgetPerPerson()); // 110 x 0.85 = 93.5 -> 94, NOT 85x0.85
    }

    @Test
    void directionPulseIsConsumedAndResetAfterMerge() {
        // 防回归：direction 是脉冲型，merge 消费后必须清零，否则下一轮无关请求会误收紧 15%。
        DecisionRecommendation focused = new DecisionRecommendation();
        focused.setShopId(2L); focused.setAvgPrice(120L);
        DecisionConstraints delta = new DecisionConstraints();
        delta.setBudgetDirection(-1);

        CriteriaMergeResult result = merger.merge(new DecisionConstraints(), delta, "好贵啊",
                java.util.Arrays.asList(focused), 2L, java.util.Arrays.asList(2L));

        assertEquals(102, result.getConstraints().getBudgetPerPerson());
        assertEquals(0, delta.getBudgetDirection());
        assertEquals(0, result.getConstraints().getBudgetDirection());
    }

    @Test
    void zeroDirectionNeverTightensBudgetOnUnrelatedTurn() {
        // 无相对意图的无关轮次：direction=0，预算不得被误收紧。
        DecisionRecommendation focused = new DecisionRecommendation();
        focused.setShopId(2L); focused.setAvgPrice(120L);
        DecisionConstraints delta = new DecisionConstraints();
        delta.setBudgetDirection(0);
        delta.setCuisine("日料");

        CriteriaMergeResult result = merger.merge(new DecisionConstraints(), delta, "推荐几家日料",
                java.util.Arrays.asList(focused), null, java.util.Arrays.asList(2L));

        assertEquals(-1, result.getConstraints().getBudgetPerPerson());
    }

    @Test
    void moreExpensiveDirectionIsNotAppliedAsCheapening() {
        // direction=+1（太便宜想吃好点的）暂不实现价格下界（第二批），不得误当"更便宜"收紧。
        DecisionRecommendation focused = new DecisionRecommendation();
        focused.setShopId(2L); focused.setAvgPrice(60L);
        DecisionConstraints delta = new DecisionConstraints();
        delta.setBudgetDirection(1);

        CriteriaMergeResult result = merger.merge(new DecisionConstraints(), delta, "太便宜了，想吃好点的",
                java.util.Arrays.asList(focused), 2L, java.util.Arrays.asList(2L));

        assertEquals(-1, result.getConstraints().getBudgetPerPerson());
    }


    @Test
    void openingCritiqueFallsBackToGlobalDefaultWhenCuisineNotInTable() {
        // GLM 收尾检查：DEFAULT_LEVEL 硬编码表只覆盖 18 菜系，表 miss（如闽菜）必须兜全局默认档 60，
        // 不得静默返回 null 导致锚链 no-op（R1 在未覆盖菜系上的精确复刻）。
        ConversationCriteriaMerger merger = new ConversationCriteriaMerger();
        DecisionConstraints active = new DecisionConstraints();
        active.setCuisine("闽菜");
        active.setBudgetPerPerson(-1);
        DecisionConstraints delta = new DecisionConstraints();
        delta.setCuisine("闽菜");
        delta.setBudgetDirection(-1);
        CriteriaMergeResult result = merger.merge(
                active, delta, "闽菜有点贵，平价一点",
                java.util.Collections.emptyList(), null, java.util.Collections.emptyList());
        assertEquals(51, result.getConstraints().getBudgetPerPerson()); // 60 * 0.85
    }

    @Test
    void openingCritiqueUsesCuisineDefaultAnchorWhenPoolEmpty() {
        // R1 bug（2026-09-04 复现）：开场第一句「火锅有点贵，有没有稍微平价一点的」，
        // 候选池空、无 shown、无 focused 店，锚链全空 → 此前收紧 no-op，budget 仍 -1，
        // 推荐不过滤价格（原样推人均 110 朝天门）。
        // 修复：锚链末端加菜系 DEFAULT_LEVEL（火锅=60），budget=60*0.85=51。
        ConversationCriteriaMerger merger = new ConversationCriteriaMerger();
        DecisionConstraints active = new DecisionConstraints();
        active.setCuisine("火锅");
        active.setBudgetPerPerson(-1);
        DecisionConstraints delta = new DecisionConstraints();
        delta.setCuisine("火锅");
        delta.setBudgetDirection(-1);
        CriteriaMergeResult result = merger.merge(
                active, delta, "火锅有点贵，有没有稍微平价一点的",
                java.util.Collections.emptyList(), null, java.util.Collections.emptyList());
        assertEquals(51, result.getConstraints().getBudgetPerPerson());
        assertTrue(result.getConstraints().getLockedConstraints().contains("budgetPerPerson"));
        // direction 脉冲清零
        assertEquals(0, result.getConstraints().getBudgetDirection());
    }

}
