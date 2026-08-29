package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.ai.dto.ShopReviewUpsertRequest;
import com.hmdp.ai.entity.AiReviewDocument;
import com.hmdp.ai.entity.ShopReview;
import com.hmdp.ai.mapper.AiReviewDocumentMapper;
import com.hmdp.ai.mapper.AiShopProfileMapper;
import com.hmdp.ai.mapper.ShopReviewMapper;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains the canonical merchant-review fact and its AI retrieval projection.
 * Profile recomputation is deliberately deferred to the later batch-rebuild stage.
 */
@Service
public class ShopReviewService {
    private static final String ACTIVE = "ACTIVE";
    private static final String DELETED = "DELETED";
    private static final String DOCUMENT_SOURCE_TYPE = "SHOP_REVIEW";

    @Resource private ShopReviewMapper reviewMapper;
    @Resource private AiReviewDocumentMapper documentMapper;
    @Resource private AiShopProfileMapper profileMapper;
    @Resource private ShopMapper shopMapper;
    @Resource private VectorSyncTaskService vectorSyncTaskService;

    @Transactional
    public ShopReview upsert(ShopReviewUpsertRequest request) {
        NormalizedRequest normalized = validate(request);
        ensureShopExists(normalized.shopId());
        ShopReview existing = findBySource(normalized.sourceType(), normalized.sourceKey());
        if (existing != null && sameActivePayload(existing, normalized)) return existing;

        Long previousShopId = existing == null ? null : existing.getShopId();
        ShopReview review;
        if (existing == null) {
            review = new ShopReview();
            review.setShopId(normalized.shopId());
            review.setSourceType(normalized.sourceType());
            review.setSourceKey(normalized.sourceKey());
            review.setUserId(normalized.userId());
            review.setRating(normalized.rating());
            review.setContent(normalized.content());
            review.setStatus(ACTIVE);
            review.setRevision(1L);
            if (reviewMapper.insert(review) != 1) throw new IllegalStateException("Shop review was not persisted");
        } else {
            review = existing;
            long expectedRevision = value(existing.getRevision());
            review.setShopId(normalized.shopId());
            review.setUserId(normalized.userId());
            review.setRating(normalized.rating());
            review.setContent(normalized.content());
            review.setStatus(ACTIVE);
            review.setRevision(expectedRevision + 1);
            int updated = reviewMapper.update(review, new UpdateWrapper<ShopReview>()
                    .eq("id", existing.getId()).eq("revision", expectedRevision));
            if (updated != 1) throw new IllegalStateException("Shop review changed concurrently");
        }
        AiReviewDocument document = upsertDocument(review);
        vectorSyncTaskService.enqueueReviewUpsert(document);
        markDirty(review.getShopId());
        if (previousShopId != null && !previousShopId.equals(review.getShopId())) markDirty(previousShopId);
        return review;
    }

    @Transactional
    public boolean delete(String sourceType, String sourceKey) {
        String normalizedType = required(sourceType, "sourceType", 32).toUpperCase(java.util.Locale.ROOT);
        String normalizedKey = required(sourceKey, "sourceKey", 128);
        ShopReview review = findBySource(normalizedType, normalizedKey);
        if (review == null || DELETED.equals(review.getStatus())) return false;
        long expectedRevision = value(review.getRevision());
        review.setStatus(DELETED);
        review.setRevision(expectedRevision + 1);
        int updated = reviewMapper.update(review, new UpdateWrapper<ShopReview>()
                .eq("id", review.getId()).eq("revision", expectedRevision));
        if (updated != 1) throw new IllegalStateException("Shop review changed concurrently");
        AiReviewDocument document = documentMapper.selectOne(new QueryWrapper<AiReviewDocument>()
                .eq("source_review_id", review.getId()).last("limit 1"));
        if (document != null) {
            vectorSyncTaskService.enqueueReviewDelete(document, review.getRevision());
            documentMapper.deleteById(document.getId());
        }
        markDirty(review.getShopId());
        return true;
    }

    private AiReviewDocument upsertDocument(ShopReview review) {
        AiReviewDocument document = documentMapper.selectOne(new QueryWrapper<AiReviewDocument>()
                .eq("source_review_id", review.getId()));
        if (document == null) {
            document = new AiReviewDocument();
            document.setSourceReviewId(review.getId());
            document.setSourceType(DOCUMENT_SOURCE_TYPE);
            document.setSourceKey(documentSourceKey(review));
        }
        document.setShopId(review.getShopId());
        document.setSourceRevision(review.getRevision());
        document.setContent(review.getContent());
        document.setTags("");
        document.setSentiment(sentiment(review.getRating()));
        if (document.getId() == null) {
            if (documentMapper.insert(document) != 1) throw new IllegalStateException("Review projection was not persisted");
        } else if (documentMapper.updateById(document) != 1) {
            throw new IllegalStateException("Review projection was not updated");
        }
        return document;
    }

    private void markDirty(Long shopId) {
        if (profileMapper.markDirty(shopId) != 1) throw new IllegalStateException("Profile was not marked for rebuild");
    }

    private ShopReview findBySource(String sourceType, String sourceKey) {
        return reviewMapper.selectOne(new QueryWrapper<ShopReview>()
                .eq("source_type", sourceType).eq("source_key", sourceKey).last("limit 1"));
    }

    private void ensureShopExists(Long shopId) {
        Shop shop = shopMapper.selectById(shopId);
        if (shop == null) throw new IllegalArgumentException("Shop does not exist");
    }

    private NormalizedRequest validate(ShopReviewUpsertRequest request) {
        if (request == null || request.getShopId() == null || request.getShopId() <= 0) {
            throw new IllegalArgumentException("shopId is required");
        }
        Integer rating = request.getRating();
        if (rating != null && (rating < 1 || rating > 5)) throw new IllegalArgumentException("rating must be between 1 and 5");
        return new NormalizedRequest(request.getShopId(),
                required(request.getSourceType(), "sourceType", 32).toUpperCase(java.util.Locale.ROOT),
                required(request.getSourceKey(), "sourceKey", 128), request.getUserId(), rating,
                required(request.getContent(), "content", 2048));
    }

    private boolean sameActivePayload(ShopReview review, NormalizedRequest request) {
        return ACTIVE.equals(review.getStatus()) && review.getShopId().equals(request.shopId())
                && java.util.Objects.equals(review.getUserId(), request.userId())
                && java.util.Objects.equals(review.getRating(), request.rating())
                && java.util.Objects.equals(review.getContent(), request.content());
    }

    private String documentSourceKey(ShopReview review) {
        return "shop-review:" + review.getSourceType() + ":" + review.getSourceKey();
    }

    private int sentiment(Integer rating) {
        if (rating == null) return 0;
        return rating >= 4 ? 1 : rating <= 2 ? -1 : 0;
    }

    private long value(Long value) { return value == null ? 0L : value; }

    private String required(String value, String field, int limit) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " is required");
        String normalized = value.trim();
        if (normalized.length() > limit) throw new IllegalArgumentException(field + " exceeds " + limit + " characters");
        return normalized;
    }

    private record NormalizedRequest(Long shopId, String sourceType, String sourceKey,
                                     Long userId, Integer rating, String content) {
    }
}
