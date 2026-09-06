package com.hmdp.ai.service;

import com.hmdp.ai.dto.AgentSessionContext;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.dto.RecommendationBatch;
import com.hmdp.ai.dto.RecommendationCandidateRef;
import java.util.ArrayList;
import java.util.List;

/** Resolves shop references without losing recommendation-batch boundaries. */
public final class BatchAwareReferenceResolver {
    public Resolution resolve(String message, AgentSessionContext context) {
        if (message == null || context == null) return null;
        int ordinal = ordinal(message);
        if (ordinal > 0) {
            boolean earliest = message.contains("最开始");
            RecommendationBatch batch = earliest ? firstNonEmpty(context.getRecommendationBatches())
                    : latestNonEmpty(context.getRecommendationBatches());
            // An invalidation is a hard boundary. Do not resurrect its predecessor for a casual ordinal.
            if (batch == null || (!earliest && latestIsEmpty(context.getRecommendationBatches()))) return null;
            if (batch.getCandidates().size() < ordinal) return null;
            RecommendationCandidateRef candidate = batch.getCandidates().get(ordinal - 1);
            return new Resolution(batch, ordinal, candidate.getShopId(), candidate.getShopName());
        }
        if (message.contains("刚才那家") || message.contains("这家") || message.contains("那家")) {
            Long focusedId = context.getFocusedShopId();
            if (focusedId != null) {
                for (RecommendationBatch batch : safe(context.getRecommendationBatches())) {
                    for (RecommendationCandidateRef candidate : safe(batch.getCandidates())) {
                        if (focusedId.equals(candidate.getShopId())) return new Resolution(batch, indexOf(batch, focusedId), candidate.getShopId(), candidate.getShopName());
                    }
                }
            }
        }
        return null;
    }

    private int ordinal(String message) {
        if (message.contains("第一家") || message.contains("首选")) return 1;
        if (message.contains("第二家")) return 2;
        if (message.contains("第三家")) return 3;
        return 0;
    }

    private RecommendationBatch latestNonEmpty(List<RecommendationBatch> batches) {
        List<RecommendationBatch> safe = safe(batches);
        for (int i = safe.size() - 1; i >= 0; i--) if (!safe(safe.get(i).getCandidates()).isEmpty()) return safe.get(i);
        return null;
    }

    private RecommendationBatch firstNonEmpty(List<RecommendationBatch> batches) {
        for (RecommendationBatch batch : safe(batches)) if (!safe(batch.getCandidates()).isEmpty()) return batch;
        return null;
    }

    private boolean latestIsEmpty(List<RecommendationBatch> batches) {
        List<RecommendationBatch> safe = safe(batches);
        return !safe.isEmpty() && safe(safe.get(safe.size() - 1).getCandidates()).isEmpty();
    }

    private int indexOf(RecommendationBatch batch, Long shopId) {
        List<RecommendationCandidateRef> candidates = safe(batch.getCandidates());
        for (int i = 0; i < candidates.size(); i++) if (shopId.equals(candidates.get(i).getShopId())) return i + 1;
        return 0;
    }

    private <T> List<T> safe(List<T> values) { return values == null ? new ArrayList<T>() : values; }

    public record Resolution(RecommendationBatch batch, int ordinal, Long shopId, String shopName) {
        public DecisionRecommendation recommendation() {
            DecisionRecommendation item = new DecisionRecommendation(); item.setShopId(shopId); item.setShopName(shopName); return item;
        }
    }
}
