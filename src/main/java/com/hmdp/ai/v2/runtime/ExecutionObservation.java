package com.hmdp.ai.v2.runtime;

import com.hmdp.ai.v2.evidence.EvidenceBundle;
import com.hmdp.ai.v2.plan.ExecutionAction;
import java.util.List;

/** Immutable executor output. It deliberately has no reference to WorkingMemory or a repository. */
public record ExecutionObservation(String requestId, Status status, List<Long> candidateShopIds,
                                   EvidenceBundle evidence, List<ExecutionAction.DomainEffect> effects,
                                   String generalAnswer, String detail, ObservationValue value) {
    public enum Status { SUCCESS, EMPTY, FAILURE, TIMEOUT, CANCELLED, SKIPPED }
    public ExecutionObservation {
        candidateShopIds = candidateShopIds == null ? List.of() : List.copyOf(candidateShopIds);
        effects = effects == null ? List.of() : List.copyOf(effects);
    }
    public ExecutionObservation(String requestId, Status status, List<Long> candidateShopIds,
                                EvidenceBundle evidence, List<ExecutionAction.DomainEffect> effects,
                                String generalAnswer, String detail) {
        this(requestId, status, candidateShopIds, evidence, effects, generalAnswer, detail, null);
    }
    public static ExecutionObservation skipped(String requestId, String detail) {
        return new ExecutionObservation(requestId, Status.SKIPPED, List.of(), null, List.of(), null, detail);
    }
}
