package com.hmdp.ai.dto;

/** Deterministic result of resolving one structured ReferenceIntent. */
public record ResolvedShopReference(ReferenceIntent intent, RecommendationBatch batch,
                                    int ordinal, Long shopId, String shopName) {
    public DecisionRecommendation recommendation() {
        DecisionRecommendation item = new DecisionRecommendation();
        item.setShopId(shopId);
        item.setShopName(shopName);
        return item;
    }
}
