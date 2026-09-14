package com.hmdp.ai.v2.grounding;

import java.util.List;

/** Read model supplied to grounding; it deliberately has no WorkingMemory writer. */
public record TaskView(String taskId, int creationOrder, String goalCategory, String city,
                       RecommendationBatchView currentVisibleBatch, Long focusedShopId) {
    public record RecommendationBatchView(String batchId, List<ShopView> candidates) {
        public RecommendationBatchView { candidates = candidates == null ? List.of() : List.copyOf(candidates); }
    }
    public record ShopView(long shopId, String name) { }
}
