package com.hmdp.ai.service.pipeline;

public class PolicyGuardNode implements ChatPipelineNode {
    private final ChatPipelineOperations operations;
    public PolicyGuardNode(ChatPipelineOperations operations) { this.operations = operations; }
    @Override public void process(ChatProcessingContext context) { operations.applyPolicyGuard(context); }
    @Override public String statusMessage() { return "正在进行安全策略检查"; }
}
