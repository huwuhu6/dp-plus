package com.hmdp.ai.v2.grounding;

import com.hmdp.ai.v2.semantic.EntityFeedback;
import java.util.List;

public record GroundedFeedback(EntityFeedback feedback, List<GroundedReference> operands) {
    public GroundedFeedback { operands = operands == null ? List.of() : List.copyOf(operands); }
}
