package com.hmdp.ai.v2.plan;

import com.hmdp.ai.v2.grounding.GroundedReference;
import com.hmdp.ai.v2.semantic.UserRequest;
import java.time.Duration;
import java.util.List;

/** Adaptive research is read-only: it returns evidence, never a WorkingMemory mutation or writer capability. */
public record AdaptiveResearchContract(List<GroundedReference.ShopIdentity> entityWhitelist,
                                       List<UserRequest.FactType> allowedReadOnlyTools, int maxSteps,
                                       int maxToolCalls, Duration deadline, boolean stopOnNoProgress,
                                       boolean stopOnDuplicateAction) {
    public AdaptiveResearchContract { entityWhitelist = List.copyOf(entityWhitelist); allowedReadOnlyTools = List.copyOf(allowedReadOnlyTools); }
}
