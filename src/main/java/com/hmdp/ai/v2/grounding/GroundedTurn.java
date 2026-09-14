package com.hmdp.ai.v2.grounding;

import com.hmdp.ai.v2.semantic.TurnSemantics;
import java.util.List;

public record GroundedTurn(TurnSemantics semantics, EffectiveTaskContext effectiveTaskContext,
                           List<GroundedRequest> requests, List<GroundedFeedback> feedback) {
    public GroundedTurn {
        requests = List.copyOf(requests);
        feedback = feedback == null ? List.of() : List.copyOf(feedback);
    }
}
