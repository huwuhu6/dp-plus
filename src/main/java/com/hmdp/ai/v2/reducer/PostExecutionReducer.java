package com.hmdp.ai.v2.reducer;

import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.dto.RecommendationBatch;
import com.hmdp.ai.dto.RecommendationCandidateRef;
import com.hmdp.ai.v2.plan.ExecutionAction;
import com.hmdp.ai.v2.runtime.ExecutionObservation;
import com.hmdp.ai.v2.runtime.StaticPlanExecutor;
import java.util.ArrayList;
import java.util.List;

/** Owns only state justified by successfully executed and verified observations. */
public final class PostExecutionReducer {
    public V2TaskState apply(V2TaskState previous, ExecutionAction.DomainEffect effect) {
        if (!(effect instanceof ExecutionAction.DomainEffect.ConditionalCriteriaApplied applied)) return previous;
        return copy(previous, com.hmdp.ai.v2.semantic.CriteriaPatchApplier.apply(previous.criteria(), applied.patch()),
                previous.selectedShopId(), previous.recommendationBatches());
    }

    public V2TaskState apply(V2TaskState previous, StaticPlanExecutor.ExecutionResult execution) {
        var criteria = previous.criteria(); Long selected = previous.selectedShopId();
        List<RecommendationBatch> batches = new ArrayList<>(previous.recommendationBatches());
        for (ExecutionObservation observation : execution.observations()) {
            if (observation.status() != ExecutionObservation.Status.SUCCESS && observation.status() != ExecutionObservation.Status.EMPTY) continue;
            for (ExecutionAction.DomainEffect effect : observation.effects()) {
                if (effect instanceof ExecutionAction.DomainEffect.ConditionalCriteriaApplied conditional)
                    criteria = com.hmdp.ai.v2.semantic.CriteriaPatchApplier.apply(criteria, conditional.patch());
                if (effect instanceof ExecutionAction.DomainEffect.CandidateSelected choice) selected = choice.shopId();
            }
            if (!observation.recommendations().isEmpty()) {
                RecommendationBatch batch = new RecommendationBatch(); batch.setBatchId("V2-" + (batches.size() + 1));
                for (DecisionRecommendation item : observation.recommendations()) {
                    RecommendationCandidateRef ref = new RecommendationCandidateRef(); ref.setShopId(item.getShopId());
                    ref.setShopName(item.getShopName()); ref.setPricePerPerson(item.getAvgPrice()); ref.setDistanceKm(item.getDistanceKm());
                    ref.setCuisine(item.getCuisine()); ref.setReferenceTags(item.getReferenceTags()); batch.getCandidates().add(ref);
                }
                batches.add(batch);
            }
        }
        return copy(previous, criteria, selected, batches);
    }

    private V2TaskState copy(V2TaskState p, com.hmdp.ai.v2.semantic.DiningCriteria criteria, Long selected, List<RecommendationBatch> batches) {
        return new V2TaskState(p.lifecycle(), criteria, p.relativePreferences(), p.relaxable(), p.locked(), p.rejectedShopIds(),
                p.feedbackLedger(), p.searchAnchor(), selected, batches);
    }
}
