package com.hmdp.ai.service;

import com.hmdp.ai.dto.SemanticRecallResult;
import com.hmdp.ai.entity.AiReviewDocument;
import com.hmdp.ai.entity.AiShopProfile;
import com.hmdp.entity.Shop;

import java.util.List;
import java.util.Map;

public interface SemanticShopRetriever {
    SemanticRecallResult recall(String query, List<Shop> hardMatchedShops,
                                Map<Long, AiShopProfile> profiles,
                                Map<Long, List<AiReviewDocument>> reviewsByShopId);

    int rebuildIndex();
}
