package com.hmdp.ai.v2.grounding;

import java.util.List;

/** Read model supplied to grounding; it deliberately has no WorkingMemory writer. */
public record TaskView(String taskId, int creationOrder, String goalCategory, String city,
                       RecommendationBatchView currentVisibleBatch, Long focusedShopId,
                       List<RecommendationBatchView> recommendationBatches) {
    public TaskView(String taskId, int creationOrder, String goalCategory, String city,
                    RecommendationBatchView currentVisibleBatch, Long focusedShopId) {
        this(taskId, creationOrder, goalCategory, city, currentVisibleBatch, focusedShopId,
                currentVisibleBatch == null ? List.of() : List.of(currentVisibleBatch));
    }
    public TaskView { recommendationBatches = recommendationBatches == null ? List.of() : List.copyOf(recommendationBatches); }
    public record RecommendationBatchView(String batchId, List<ShopView> candidates) {
        public RecommendationBatchView { candidates = candidates == null ? List.of() : List.copyOf(candidates); }
    }
    public record ShopView(long shopId, String name,String cuisine) {
        public ShopView(long shopId,String name){this(shopId,name,null);}
        public boolean matchesDescription(String literal){return name!=null && name.contains(literal)
                || cuisine!=null && java.util.Arrays.stream(cuisine.split("[,，、]")).anyMatch(c->c.trim().equals(literal));}
    }
}
