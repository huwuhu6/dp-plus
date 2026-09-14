package com.hmdp.ai.v2.runtime;

import com.hmdp.ai.v2.evidence.EvidenceBundle;
import com.hmdp.ai.v2.plan.ExecutionAction;
import com.hmdp.ai.dto.DecisionRecommendation;
import java.util.List;

/** Immutable executor output. It deliberately has no reference to WorkingMemory or a repository. */
public record ExecutionObservation(String requestId, Status status, List<Long> candidateShopIds,
                                   EvidenceBundle evidence, List<ExecutionAction.DomainEffect> effects,
                                   String generalAnswer, String detail, ObservationValue value,
                                   List<DecisionRecommendation> recommendations) {
    public enum Status { SUCCESS, EMPTY, FAILURE, TIMEOUT, CANCELLED, SKIPPED, UNSUPPORTED }
    public ExecutionObservation {
        candidateShopIds = candidateShopIds == null ? List.of() : List.copyOf(candidateShopIds);
        effects = effects == null ? List.of() : List.copyOf(effects);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
    }
    public ExecutionObservation(String requestId, Status status, List<Long> candidateShopIds,
                                EvidenceBundle evidence, List<ExecutionAction.DomainEffect> effects,
                                String generalAnswer, String detail) {
        this(requestId, status, candidateShopIds, evidence, effects, generalAnswer, detail, null, List.of());
    }
    public ExecutionObservation(String requestId, Status status, List<Long> candidateShopIds,
                                EvidenceBundle evidence, List<ExecutionAction.DomainEffect> effects,
                                String generalAnswer, String detail, ObservationValue value) {
        this(requestId, status, candidateShopIds, evidence, effects, generalAnswer, detail, value, List.of());
    }
    public static ExecutionObservation skipped(String requestId, String detail) {
        return new ExecutionObservation(requestId, Status.SKIPPED, List.of(), null, List.of(), null, detail);
    }
}
