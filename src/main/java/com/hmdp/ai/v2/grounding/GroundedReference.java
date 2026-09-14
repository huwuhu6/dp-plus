package com.hmdp.ai.v2.grounding;

public sealed interface GroundedReference permits GroundedReference.ShopIdentity, GroundedReference.TaskIdentity {
    record ShopIdentity(long shopId, String batchId, int ordinal) implements GroundedReference { }
    record TaskIdentity(String taskId) implements GroundedReference { }
}
