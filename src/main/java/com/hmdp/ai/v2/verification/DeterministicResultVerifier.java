package com.hmdp.ai.v2.verification;

import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.v2.plan.SearchSpec;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Deterministic candidate gate. It reports and filters; it never mutates state or runs another search. */
public final class DeterministicResultVerifier implements ResultVerifier {
    @Override public VerificationReport verify(List<String> failures, List<String> evidenceGaps) {
        List<String> safeFailures = failures == null ? List.of() : List.copyOf(failures);
        return new VerificationReport(safeFailures.isEmpty(), safeFailures, evidenceGaps == null ? List.of() : List.copyOf(evidenceGaps));
    }

    public VerificationReport verify(SearchSpec spec, List<DecisionRecommendation> candidates) {
        List<String> failures = new ArrayList<>();
        List<String> evidenceGaps = new ArrayList<>();
        List<DecisionRecommendation> verified = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        BigDecimal hardMax = spec.criteria().budget() == null ? null : spec.criteria().budget().hardMax();
        BigDecimal hardDistance = spec.criteria().distance() == null ? null : spec.criteria().distance().hardMaxKm();
        for (DecisionRecommendation candidate : candidates == null ? List.<DecisionRecommendation>of() : candidates) {
            String id = candidate == null || candidate.getShopId() == null ? "unknown" : candidate.getShopId().toString();
            boolean valid = true;
            if (candidate == null || candidate.getShopId() == null) {
                failures.add("INSUFFICIENT_DATA:shop_identity:" + id); valid = false;
            } else if (!seen.add(candidate.getShopId())) {
                failures.add("DUPLICATE:shop:" + id); valid = false;
            }
            if (candidate != null && candidate.getShopId() != null && spec.excludedShopIds().contains(candidate.getShopId())) {
                failures.add("REJECTED_OR_TEMPORARILY_EXCLUDED:shop:" + id); valid = false;
            }
            if (candidate != null && hardMax != null) {
                if (candidate.getAvgPrice() == null) {
                    failures.add("INSUFFICIENT_DATA:budget:" + id); valid = false;
                } else if (BigDecimal.valueOf(candidate.getAvgPrice()).compareTo(hardMax) > 0) {
                    failures.add("HARD_BUDGET:shop:" + id); valid = false;
                }
            }
            if (candidate != null && hardDistance != null) {
                boolean anchorValid = spec.searchAnchor() != null && spec.searchAnchor().latitude() != null
                        && spec.searchAnchor().longitude() != null;
                if (!anchorValid || candidate.getDistanceKm() == null) {
                    failures.add("INSUFFICIENT_DATA:distance:" + id); valid = false;
                } else if (BigDecimal.valueOf(candidate.getDistanceKm()).compareTo(hardDistance) > 0) {
                    failures.add("HARD_DISTANCE:shop:" + id); valid = false;
                }
            }
            if (candidate != null && valid) verified.add(candidate);
            if (candidate != null) addEvidenceGaps(spec, candidate, evidenceGaps);
        }
        return new VerificationReport(failures.isEmpty(), failures, evidenceGaps, verified);
    }

    private void addEvidenceGaps(SearchSpec spec, DecisionRecommendation candidate, List<String> gaps) {
        if (spec.criteria().preferences() == null) return;
        for (var preference : spec.criteria().preferences().values()) {
            String criterion = preference.criterion();
            String needle = criterion.toLowerCase(Locale.ROOT);
            boolean supported = candidate.getReferenceTags().stream().anyMatch(value -> contains(value, needle))
                    || candidate.getMatchedReasons().stream().anyMatch(value -> contains(value, needle))
                    || candidate.getEvidence().stream().anyMatch(value -> contains(value, needle));
            if (!supported) gaps.add("INSUFFICIENT_EVIDENCE:" + criterion + ":" + candidate.getShopId());
        }
    }
    private boolean contains(String source, String needle) {
        return source != null && source.toLowerCase(Locale.ROOT).contains(needle);
    }
}
