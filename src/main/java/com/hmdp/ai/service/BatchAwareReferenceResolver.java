package com.hmdp.ai.service;

import com.hmdp.ai.dto.AgentSessionContext;
import com.hmdp.ai.dto.RecommendationBatch;
import com.hmdp.ai.dto.RecommendationCandidateRef;
import com.hmdp.ai.dto.ReferenceIntent;
import com.hmdp.ai.dto.ResolvedShopReference;
import java.util.ArrayList;
import java.util.List;

/** Resolves shop references without losing recommendation-batch boundaries. */
public final class BatchAwareReferenceResolver {
    public ResolvedShopReference resolve(ReferenceIntent intent, AgentSessionContext context) {
        if (intent == null || context == null) return null;
        if (intent.getOrdinal() != null) return resolveOrdinal(intent, context);
        if (hasText(intent.getQualifier())) return resolveQualifier(intent, context);
        if (intent.getScope() == ReferenceIntent.Scope.FOCUSED) return resolveFocused(intent, context);
        return null;
    }

    /** Returns all deterministic qualifier matches so the caller can surface ambiguity. */
    public List<ResolvedShopReference> qualifierMatches(ReferenceIntent intent, AgentSessionContext context) {
        List<ResolvedShopReference> matches = new ArrayList<>();
        RecommendationBatch batch = selectBatch(intent, context);
        if (batch == null || !hasText(intent == null ? null : intent.getQualifier())) return matches;
        List<RecommendationCandidateRef> candidates = safe(batch.getCandidates());
        for (int index = 0; index < candidates.size(); index++) {
            RecommendationCandidateRef candidate = candidates.get(index);
            if (matchesQualifier(candidate, intent.getQualifier())) {
                matches.add(new ResolvedShopReference(intent, batch, index + 1, candidate.getShopId(), candidate.getShopName()));
            }
        }
        return matches;
    }

    private ResolvedShopReference resolveOrdinal(ReferenceIntent intent, AgentSessionContext context) {
        int ordinal = intent.getOrdinal() == null ? 0 : intent.getOrdinal();
        if (ordinal < 1) return null;
        List<RecommendationBatch> batches = safe(context.getRecommendationBatches());
        RecommendationBatch batch = intent.getScope() == ReferenceIntent.Scope.EARLIEST
                ? firstNonEmpty(batches) : latestNonEmpty(batches);
        // An empty latest batch is an invalidation boundary for an ordinary ordinal.
        if (batch == null || (intent.getScope() != ReferenceIntent.Scope.EARLIEST && latestIsEmpty(batches))) return null;
        List<RecommendationCandidateRef> candidates = safe(batch.getCandidates());
        if (candidates.size() < ordinal) return null;
        RecommendationCandidateRef candidate = candidates.get(ordinal - 1);
        return new ResolvedShopReference(intent, batch, ordinal, candidate.getShopId(), candidate.getShopName());
    }

    private ResolvedShopReference resolveQualifier(ReferenceIntent intent, AgentSessionContext context) {
        List<ResolvedShopReference> matches = qualifierMatches(intent, context);
        return matches.size() == 1 ? matches.get(0) : null;
    }

    public List<ResolvedShopReference> resolveAll(List<ReferenceIntent> intents, AgentSessionContext context) {
        List<ResolvedShopReference> resolved = new ArrayList<>();
        if (intents == null) return resolved;
        for (ReferenceIntent intent : intents) {
            ResolvedShopReference item = resolve(intent, context);
            if (item != null) resolved.add(item);
        }
        return resolved;
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

    private ResolvedShopReference resolveFocused(ReferenceIntent intent, AgentSessionContext context) {
        Long focusedId = context.getFocusedShopId();
        if (focusedId == null) return null;
        for (RecommendationBatch batch : safe(context.getRecommendationBatches())) {
            List<RecommendationCandidateRef> candidates = safe(batch.getCandidates());
            for (int index = 0; index < candidates.size(); index++) {
                RecommendationCandidateRef candidate = candidates.get(index);
                if (focusedId.equals(candidate.getShopId())) {
                    return new ResolvedShopReference(intent, batch, index + 1,
                            candidate.getShopId(), candidate.getShopName());
                }
            }
        }
        return null;
    }

    private RecommendationBatch selectBatch(ReferenceIntent intent, AgentSessionContext context) {
        List<RecommendationBatch> batches = safe(context.getRecommendationBatches());
        if (intent != null && intent.getScope() == ReferenceIntent.Scope.EARLIEST) return firstNonEmpty(batches);
        return latestNonEmpty(batches);
    }

    private boolean matchesQualifier(RecommendationCandidateRef candidate, String qualifier) {
        String target = normalize(qualifier);
        if (target.isEmpty() || candidate == null) return false;
        if (normalize(candidate.getShopName()).contains(target)) return true;
        if (normalize(candidate.getCuisine()).contains(target)) return true;
        if (candidate.getReferenceTags() != null) {
            for (String tag : candidate.getReferenceTags()) if (normalize(tag).contains(target)) return true;
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\s，。！？、]", "").trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private <T> List<T> safe(List<T> values) { return values == null ? new ArrayList<T>() : values; }

}
