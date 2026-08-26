package com.hmdp.ai.service.pipeline;

public class PolicyGuardNode implements ChatPipelineNode {
    private final ChatPipelineOperations operations;
    public PolicyGuardNode(ChatPipelineOperations operations) { this.operations = operations; }
    @Override public void process(ChatProcessingContext context) { operations.applyPolicyGuard(context); }
}
