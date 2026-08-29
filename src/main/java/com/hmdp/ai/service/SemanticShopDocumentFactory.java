package com.hmdp.ai.service;

import com.hmdp.ai.entity.AiReviewDocument;
import com.hmdp.ai.entity.AiShopProfile;
import com.hmdp.entity.Shop;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** Owns the stable vector document contract shared by full and incremental indexing. */
@Component
public class SemanticShopDocumentFactory {
    public static final String PROFILE = "PROFILE";
    public static final String REVIEW = "REVIEW";

    public String profileDocumentId(Long shopId) { return "shop-profile-" + shopId; }
    public String reviewDocumentId(Long reviewDocumentId) { return "shop-review-" + reviewDocumentId; }

    public Document profileDocument(Shop shop, AiShopProfile profile) {
        Map<String, Object> metadata = metadata(shop.getId(), PROFILE);
        metadata.put("profileRevision", value(profile.getAggregatedRevision()));
        String text = "商户：" + shop.getName()
                + "。菜系：" + safe(profile.getCuisine())
                + "。场景：" + safe(profile.getSceneTags())
                + "。环境：" + safe(profile.getAmbienceTags())
                + "。简介：" + safe(profile.getSummary());
        return new Document(profileDocumentId(shop.getId()), text, metadata);
    }

    public Document reviewDocument(Shop shop, AiReviewDocument review) {
        Map<String, Object> metadata = metadata(shop.getId(), REVIEW);
        metadata.put("reviewId", review.getId());
        metadata.put("sourceType", safe(review.getSourceType()));
        metadata.put("sourceRevision", value(review.getSourceRevision()));
        String text = "商户：" + shop.getName() + "。评价证据：" + safe(review.getContent())
                + "。标签：" + safe(review.getTags());
        return new Document(reviewDocumentId(review.getId()), text, metadata);
    }

    private Map<String, Object> metadata(Long shopId, String documentType) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("shopId", shopId);
        metadata.put("documentType", documentType);
        return metadata;
    }

    private long value(Long value) { return value == null ? 0L : value; }
    private String safe(String value) { return value == null ? "" : value; }
}
