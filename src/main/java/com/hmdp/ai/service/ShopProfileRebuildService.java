package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.entity.AiShopProfile;
import com.hmdp.ai.entity.ShopReview;
import com.hmdp.ai.mapper.AiShopProfileMapper;
import com.hmdp.ai.mapper.ShopReviewMapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/** Rebuilds profiles outside the review-write transaction and commits only a matching input revision. */
@Service
public class ShopProfileRebuildService {
    private static final Logger log = LoggerFactory.getLogger(ShopProfileRebuildService.class);
    private static final String WAIT_REBUILD = "WAIT_REBUILD";
    @Resource private AiShopProfileMapper profileMapper;
    @Resource private ShopReviewMapper reviewMapper;
    @Resource private ShopProfileDraftProvider draftGenerator;
    @Resource private AiProperties aiProperties;

    public int rebuildPendingProfiles() {
        return rebuildPendingProfiles(aiProperties.getProfileRebuild().getBatchSize());
    }

    public int rebuildPendingProfiles(Integer requestedBatchSize) {
        int batchSize = Math.max(1, Math.min(requestedBatchSize == null ? 20 : requestedBatchSize, 100));
        List<AiShopProfile> profiles = profileMapper.selectList(new QueryWrapper<AiShopProfile>()
                .eq("profile_status", WAIT_REBUILD).orderByAsc("update_time").last("limit " + batchSize));
        int completed = 0;
        for (AiShopProfile profile : profiles) if (rebuild(profile.getShopId())) completed++;
        return completed;
    }

    public boolean rebuild(Long shopId) {
        AiShopProfile profile = profileMapper.selectOne(new QueryWrapper<AiShopProfile>()
                .eq("shop_id", shopId).eq("profile_status", WAIT_REBUILD).last("limit 1"));
        if (profile == null) return false;
        long expectedRevision = value(profile.getInputRevision());
        List<ShopReview> reviews = reviewMapper.selectList(new QueryWrapper<ShopReview>()
                .eq("shop_id", shopId).eq("status", "ACTIVE").orderByDesc("id"));
        if (reviews.isEmpty()) {
            return profileMapper.completeWithoutReviews(shopId, expectedRevision) == 1;
        }
        try {
            ShopProfileDraftGenerator.ProfileDraft draft = draftGenerator.generate(profile, reviews);
            String queueLevel = queueLevel(reviews);
            boolean completed = profileMapper.completeRebuild(shopId, expectedRevision,
                    String.join(",", draft.sceneTags()), String.join(",", draft.ambienceTags()),
                    queueLevel, draft.summary()) == 1;
            if (!completed) log.info("[AI][profile] event=REBUILD_STALE shopId={} expectedRevision={}", shopId, expectedRevision);
            return completed;
        } catch (Exception e) {
            log.warn("[AI][profile] event=REBUILD_FAILED shopId={} expectedRevision={} errorType={}",
                    shopId, expectedRevision, e.getClass().getSimpleName());
            return false;
        }
    }

    private String queueLevel(List<ShopReview> reviews) {
        int waitSignals = 0;
        int lowSignals = 0;
        for (ShopReview review : reviews) {
            String content = review.getContent() == null ? "" : review.getContent();
            if (containsAny(content, "不用排队", "不排队", "无需等位", "基本不用等")) lowSignals++;
            else if (containsAny(content, "排队", "等位", "排号", "拥挤")) waitSignals++;
        }
        if (waitSignals >= 2 || (reviews.size() >= 4 && waitSignals * 4 >= reviews.size())) return "HIGH";
        if (waitSignals == 1) return "MEDIUM";
        if (lowSignals > 0) return "LOW";
        return "UNKNOWN";
    }

    private boolean containsAny(String content, String... values) {
        for (String value : values) if (content.contains(value)) return true;
        return false;
    }

    private long value(Long value) { return value == null ? 0L : value; }
}
