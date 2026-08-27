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
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MilvusSemanticShopRetriever implements SemanticShopRetriever {
    private static final Logger log = LoggerFactory.getLogger(MilvusSemanticShopRetriever.class);
    // DashScope text-embedding-v4 accepts at most ten input texts per request.
    private static final int EMBEDDING_BATCH_SIZE = 10;

    @Autowired(required = false) private VectorStore vectorStore;
    @Resource private ShopMapper shopMapper;
    @Resource private AiShopProfileMapper profileMapper;
    @Resource private AiReviewDocumentMapper reviewMapper;
    @Value("${ai.retrieval.semantic-top-k:80}") private int semanticTopK;
    @Value("${ai.retrieval.semantic-min-score:0.35}") private double semanticMinScore;

    @Override
    public SemanticRecallResult recall(String query, List<Shop> hardMatchedShops,
                                       Map<Long, AiShopProfile> profiles,
                                       Map<Long, List<AiReviewDocument>> reviewsByShopId) {
        SemanticRecallResult result = SemanticRecallResult.unavailable();
        if (vectorStore == null || query == null || query.trim().isEmpty() || hardMatchedShops == null || hardMatchedShops.isEmpty()) {
            return result;
        }
        long startedAt = System.currentTimeMillis();
        Set<Long> allowedShopIds = new HashSet<>();
        for (Shop shop : hardMatchedShops) allowedShopIds.add(shop.getId());
        try {
            List<Object> filterShopIds = new ArrayList<Object>(allowedShopIds);
            List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(Math.max(1, Math.min(semanticTopK, 200)))
                    .similarityThresholdAll()
                    .filterExpression(new FilterExpressionBuilder().in("shopId", filterShopIds).build())
                    .build());
            Map<Long, Double> scoreByShopId = new HashMap<>();
            int discarded = 0;
            for (Document document : documents) {
                Long shopId = toLong(document.getMetadata().get("shopId"));
                double score = document.getScore() == null ? 0D : document.getScore();
                if (shopId == null || !allowedShopIds.contains(shopId) || score < semanticMinScore) {
                    discarded++;
                    continue;
                }
                scoreByShopId.merge(shopId, score, Math::max);
            }
            result.setShopScores(scoreByShopId);
            result.setMatchedDocumentCount(documents.size());
            result.setAcceptedDocumentCount(documents.size() - discarded);
            result.setDiscardedDocumentCount(discarded);
            result.setAvailable(true);
            result.setDurationMs(System.currentTimeMillis() - startedAt);
            log.info("[AI][semantic] action=RECALL query={} hardWhitelist={} vectorDocuments={} acceptedDocuments={} discardedDocuments={} matchedShops={} minScore={} durationMs={}",
                    compact(query), allowedShopIds.size(), documents.size(), result.getAcceptedDocumentCount(),
                    result.getDiscardedDocumentCount(), scoreByShopId.size(), semanticMinScore, result.getDurationMs());
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
        for (int start = 0; start < documents.size(); start += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(start + EMBEDDING_BATCH_SIZE, documents.size());
            vectorStore.add(documents.subList(start, end));
            log.info("[AI][semantic] action=INDEX_BATCH_WRITTEN batch={}/{} documents={}",
                    start / EMBEDDING_BATCH_SIZE + 1,
                    (documents.size() + EMBEDDING_BATCH_SIZE - 1) / EMBEDDING_BATCH_SIZE,
                    end - start);
        }
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
        if (value instanceof Number) return ((Number) value).longValue();
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
