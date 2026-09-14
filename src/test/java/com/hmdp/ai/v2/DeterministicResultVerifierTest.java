package com.hmdp.ai.v2;

import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.v2.grounding.GroundedReference;
import com.hmdp.ai.v2.plan.ExecutionAction;
import com.hmdp.ai.v2.plan.SearchAnchor;
import com.hmdp.ai.v2.plan.SearchSpec;
import com.hmdp.ai.v2.semantic.DiningCriteria;
import com.hmdp.ai.v2.verification.DeterministicResultVerifier;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DeterministicResultVerifierTest {
    @Test
    void filtersOverBudgetRejectedDuplicateAndOverDistanceCandidates() {
        var spec = new SearchSpec(2, "task", ExecutionAction.SearchKind.ALTERNATIVES, 5,
                new DiningCriteria(null, null, new DiningCriteria.BudgetCriteria(null, BigDecimal.valueOf(100)),
                        new DiningCriteria.DistanceCriteria(BigDecimal.valueOf(3)), null, DiningCriteria.SemanticPreferences.empty()),
                List.of(), Set.of(9L), new GroundedReference.ShopIdentity(1L, "batch", 1),
                new SearchAnchor(null, "当前位置", 26D, 119D, null, null, null, SearchAnchor.AnchorSource.DEVICE));
        DecisionRecommendation valid = candidate(2L, 80L, 2D);
        DecisionRecommendation overBudget = candidate(3L, 130L, 1D);
        DecisionRecommendation rejected = candidate(9L, 70L, 1D);
        DecisionRecommendation overDistance = candidate(4L, 70L, 4D);
        var report = new DeterministicResultVerifier().verify(spec,
                List.of(valid, valid, overBudget, rejected, overDistance));
        assertEquals(List.of(2L), report.verifiedCandidates().stream().map(DecisionRecommendation::getShopId).toList());
        assertFalse(report.hardChecksPassed());
        assertTrue(report.failedHardChecks().stream().anyMatch(value -> value.startsWith("DUPLICATE")));
        assertTrue(report.failedHardChecks().stream().anyMatch(value -> value.startsWith("HARD_BUDGET")));
        assertTrue(report.failedHardChecks().stream().anyMatch(value -> value.startsWith("REJECTED")));
        assertTrue(report.failedHardChecks().stream().anyMatch(value -> value.startsWith("HARD_DISTANCE")));
    }

    @Test
    void missingDistanceEvidenceIsInsufficientAndCannotPass() {
        var spec = new SearchSpec(2, "task", ExecutionAction.SearchKind.RECOMMENDATIONS, 3, DiningCriteria.empty(),
                List.of(), Set.of(), null,
                new SearchAnchor(null, null, null, null, null, "福州市", null, SearchAnchor.AnchorSource.NAMED_LOCATION));
        var criteria = new DiningCriteria(null, null, null, new DiningCriteria.DistanceCriteria(BigDecimal.valueOf(2)),
                null, DiningCriteria.SemanticPreferences.empty());
        spec = new SearchSpec(spec.baseMemoryVersion(), spec.taskId(), spec.kind(), spec.count(), criteria,
                spec.relativePreferences(), spec.excludedShopIds(), null, spec.searchAnchor());
        var report = new DeterministicResultVerifier().verify(spec, List.of(candidate(1L, 50L, null)));
        assertTrue(report.verifiedCandidates().isEmpty());
        assertTrue(report.failedHardChecks().getFirst().startsWith("INSUFFICIENT_DATA:distance"));
    }

    private DecisionRecommendation candidate(Long id, Long price, Double distance) {
        DecisionRecommendation item = new DecisionRecommendation(); item.setShopId(id); item.setAvgPrice(price); item.setDistanceKm(distance); return item;
    }
}
