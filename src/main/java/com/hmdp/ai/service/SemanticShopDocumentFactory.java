package com.hmdp.ai.service;

import com.hmdp.ai.entity.AiReviewDocument;
import com.hmdp.ai.entity.AiShopProfile;
import com.hmdp.entity.Shop;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
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
        return document(profileDocumentId(shop.getId()), text, metadata);
    }

    public Document reviewDocument(Shop shop, AiReviewDocument review) {
        Map<String, Object> metadata = metadata(shop.getId(), REVIEW);
        metadata.put("reviewId", review.getId());
        metadata.put("sourceType", safe(review.getSourceType()));
        metadata.put("sourceRevision", value(review.getSourceRevision()));
        String text = "商户：" + shop.getName() + "。评价证据：" + safe(review.getContent())
                + "。标签：" + safe(review.getTags());
        return document(reviewDocumentId(review.getId()), text, metadata);
    }

    private Map<String, Object> metadata(Long shopId, String documentType) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("shopId", shopId);
        metadata.put("documentType", documentType);
        return metadata;
    }

    private Document document(String documentId, String text, Map<String, Object> metadata) {
        metadata.put("documentFingerprint", fingerprint(documentId, text, metadata));
        return new Document(documentId, text, metadata);
    }

    /** Only vector text and contract metadata participate; timestamps and retry state must never affect this value. */
    private String fingerprint(String documentId, String text, Map<String, Object> metadata) {
        ArrayList<String> keys = new ArrayList<>(metadata.keySet());
        Collections.sort(keys);
        StringBuilder canonical = new StringBuilder("v1\n").append(documentId).append('\n').append(text).append('\n');
        for (String key : keys) canonical.append(key).append('=').append(metadata.get(key)).append('\n');
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) value.append(String.format("%02x", item));
            return value.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private long value(Long value) { return value == null ? 0L : value; }
    private String safe(String value) { return value == null ? "" : value; }
}
