package com.hmdp.ai.v2.grounding;

import com.hmdp.ai.v2.semantic.UserRequest;
import java.util.List;

public record GroundedRequest(UserRequest request, List<GroundedReference> operands) {
    public GroundedRequest { operands = operands == null ? List.of() : List.copyOf(operands); }
}
