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
        previous.setOccasion("聚餐");

        DecisionConstraints delta = new DecisionConstraints();
        delta.setCuisine("粤菜");
        CriteriaMergeResult result = merger.merge(previous, delta, "不要辣的，换成粤菜，重新推荐");

        assertEquals("粤菜", result.getConstraints().getCuisine());
        assertEquals(200, result.getConstraints().getBudgetPerPerson());
        assertEquals(3D, result.getConstraints().getRadiusKm());
        assertTrue(result.getConstraints().getNearby());
        assertTrue(result.getConstraints().getSoftPreferences().contains("清淡/不辣"));
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
        previous.setHardConstraints(java.util.Arrays.asList("必吃榜"));
        previous.setSoftPreferences(java.util.Arrays.asList("安静"));
        DecisionConstraints delta = new DecisionConstraints();
        delta.setHardConstraints(java.util.Arrays.asList("必吃榜", "有停车位"));
        delta.setSoftPreferences(java.util.Arrays.asList("安静", "有包厢"));

        CriteriaMergeResult result = merger.merge(previous, delta, "有停车位和包厢吗");

        assertEquals(java.util.Arrays.asList("必吃榜", "有停车位"), result.getConstraints().getHardConstraints());
        assertEquals(java.util.Arrays.asList("安静", "有包厢"), result.getConstraints().getSoftPreferences());
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
    void derivesStrictBudgetFromFocusedCandidateForCheaperRefinement() {
        DecisionRecommendation focused = new DecisionRecommendation();
        focused.setShopId(2L); focused.setAvgPrice(120L);
        DecisionRecommendation lower = new DecisionRecommendation();
        lower.setShopId(3L); lower.setAvgPrice(80L);

        CriteriaMergeResult result = merger.merge(new DecisionConstraints(), new DecisionConstraints(), "太贵了，换个便宜点的",
                java.util.Arrays.asList(focused, lower), 2L);

        assertEquals(119, result.getConstraints().getBudgetPerPerson());
        assertTrue(result.getAppended().stream().anyMatch(item -> item.startsWith("relativeBudget:anchorPrice=120")));
    }

    @Test
    void derivesNarrowerRadiusFromFocusedCandidateForCloserRefinement() {
        DecisionRecommendation focused = new DecisionRecommendation();
        focused.setShopId(2L); focused.setDistanceKm(2.5D);

        CriteriaMergeResult result = merger.merge(new DecisionConstraints(), new DecisionConstraints(), "换个更近一点的",
                java.util.Collections.singletonList(focused), 2L);

        assertEquals(2.3D, result.getConstraints().getRadiusKm());
        assertTrue(result.getAppended().stream().anyMatch(item -> item.startsWith("relativeDistance:anchorKm=2.5")));
    }
}
