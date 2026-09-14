package com.hmdp.ai.v2.plan;

import com.hmdp.ai.v2.grounding.GroundedTurn;
import com.hmdp.ai.v2.semantic.UserRequest;

/** Static means every legal successor is enumerable before execution, not merely “a simple question”. */
public final class ExecutionModeGate {
    public ExecutionMode decide(GroundedTurn turn) {
        if (turn.requests().stream().anyMatch(r -> r.request() instanceof UserRequest.ExploreRequest explore && explore.unboundedContinuation()))
            return ExecutionMode.ADAPTIVE_RESEARCH;
        if (turn.requests().stream().anyMatch(r -> r.request() instanceof UserRequest.CompareRequest compare && compare.dimensions().size() > 1))
            return ExecutionMode.STATIC_PLAN;
        if (turn.requests().size() == 1 && turn.semantics().relations().isEmpty()
                && (turn.requests().getFirst().request() instanceof UserRequest.FactQueryRequest
                || turn.requests().getFirst().request() instanceof UserRequest.GeneralKnowledgeRequest)) return ExecutionMode.DIRECT;
        return ExecutionMode.STATIC_PLAN;
    }
}
