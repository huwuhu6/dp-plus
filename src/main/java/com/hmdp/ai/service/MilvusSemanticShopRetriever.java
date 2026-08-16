package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.ai.dto.SemanticRecallResult;
import com.hmdp.ai.entity.AiReviewDocument;
import com.hmdp.ai.entity.AiShopProfile;
import com.hmdp.ai.mapper.AiReviewDocumentMapper;
import com.hmdp.ai.mapper.AiShopProfileMapper;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@ConditionalOnProperty(prefix = "ai.retrieval", name = "vector-enabled", havingValue = "true")
public class MilvusSemanticShopRetriever implements SemanticShopRetriever {
    private static final Logger log = LoggerFactory.getLogger(MilvusSemanticShopRetriever.class);
    private static final int DEFAULT_TOP_K = 80;

    @Resource private VectorStore vectorStore;
    @Resource private ShopMapper shopMapper;
    @Resource private AiShopProfileMapper profileMapper;
    @Resource private AiReviewDocumentMapper reviewMapper;

    @Override
    public SemanticRecallResult recall(String query, List<Shop> hardMatchedShops,
                                       Map<Long, AiShopProfile> profiles,
                                       Map<Long, List<AiReviewDocument>> reviewsByShopId) {
        SemanticRecallResult result = SemanticRecallResult.unavailable();
        if (query == null || query.trim().isEmpty() || hardMatchedShops == null || hardMatchedShops.isEmpty()) {
            return result;
        }
        long startedAt = System.currentTimeMillis();
        Set<Long> allowedShopIds = new HashSet<>();
        for (Shop shop : hardMatchedShops) allowedShopIds.add(shop.getId());
        try {
            List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(DEFAULT_TOP_K)
                    .similarityThresholdAll()
                    .build());
            Map<Long, Double> scoreByShopId = new HashMap<>();
            for (Document document : documents) {
                Long shopId = toLong(document.getMetadata().get("shopId"));
                if (shopId == null || !allowedShopIds.contains(shopId)) continue;
                double score = document.getScore() == null ? 0D : document.getScore();
                scoreByShopId.merge(shopId, score, Math::max);
            }
            result.setShopScores(scoreByShopId);
            result.setMatchedDocumentCount(documents.size());
            result.setAvailable(true);
            result.setDurationMs(System.currentTimeMillis() - startedAt);
            log.info("[AI][semantic] action=RECALL query={} hardWhitelist={} vectorDocuments={} matchedShops={} durationMs={}",
                    compact(query), allowedShopIds.size(), documents.size(), scoreByShopId.size(), result.getDurationMs());
            return result;
        } catch (Exception e) {
            result.setDurationMs(System.currentTimeMillis() - startedAt);
            log.warn("[AI][semantic] action=RECALL_FALLBACK reason={} durationMs={}",
                    e.getClass().getSimpleName(), result.getDurationMs());
            return result;
        }
    }

    @Override
    public int rebuildIndex() {
        List<Shop> shops = shopMapper.selectList(null);
        Map<Long, Shop> shopsById = new HashMap<>();
        for (Shop shop : shops) shopsById.put(shop.getId(), shop);
        List<AiShopProfile> profiles = profileMapper.selectList(null);
        List<AiReviewDocument> reviews = reviewMapper.selectList(new QueryWrapper<AiReviewDocument>());
        List<Document> documents = new ArrayList<>();
        for (AiShopProfile profile : profiles) {
            Shop shop = shopsById.get(profile.getShopId());
            if (shop != null) documents.add(profileDocument(shop, profile));
        }
        for (AiReviewDocument review : reviews) {
            Shop shop = shopsById.get(review.getShopId());
            if (shop != null) documents.add(reviewDocument(shop, review));
        }
        if (documents.isEmpty()) return 0;
        List<String> ids = documents.stream().map(Document::getId).toList();
        try {
            vectorStore.delete(ids);
        } catch (Exception ignored) {
            // A fresh collection has no documents to delete.
        }
        vectorStore.add(documents);
        log.info("[AI][semantic] action=INDEX_REBUILT shops={} documents={}", shops.size(), documents.size());
        return documents.size();
    }

    private Document profileDocument(Shop shop, AiShopProfile profile) {
        Map<String, Object> metadata = metadata(shop.getId(), "PROFILE");
        String text = "商户：" + shop.getName()
                + "。菜系：" + safe(profile.getCuisine())
                + "。场景：" + safe(profile.getSceneTags())
                + "。环境：" + safe(profile.getAmbienceTags())
                + "。简介：" + safe(profile.getSummary());
        return new Document("shop-profile-" + shop.getId(), text, metadata);
    }

    private Document reviewDocument(Shop shop, AiReviewDocument review) {
        Map<String, Object> metadata = metadata(shop.getId(), "REVIEW");
        metadata.put("reviewId", review.getId());
        metadata.put("sourceType", safe(review.getSourceType()));
        String text = "商户：" + shop.getName() + "。评价证据：" + safe(review.getContent())
                + "。标签：" + safe(review.getTags());
        return new Document("shop-review-" + review.getId(), text, metadata);
    }

    private Map<String, Object> metadata(Long shopId, String documentType) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("shopId", shopId);
        metadata.put("documentType", documentType);
        return metadata;
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String compact(String value) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() > 80 ? normalized.substring(0, 80) + "..." : normalized;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
