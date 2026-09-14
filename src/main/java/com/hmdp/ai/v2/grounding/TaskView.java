package com.hmdp.ai.v2.grounding;

import java.util.List;

/** Read model supplied to grounding; it deliberately has no WorkingMemory writer. */
public record TaskView(String taskId, String label, List<RecommendationBatchView> batches, Long focusedShopId) {
    public TaskView { batches = batches == null ? List.of() : List.copyOf(batches); }
    public record RecommendationBatchView(String batchId, List<ShopView> candidates) {
        public RecommendationBatchView { candidates = candidates == null ? List.of() : List.copyOf(candidates); }
    }
    public record ShopView(long shopId, String name) { }
}
