package com.hmdp.ai.service.pipeline;

public class IntentRoutingNode implements ChatPipelineNode {
    private final ChatPipelineOperations operations;
    public IntentRoutingNode(ChatPipelineOperations operations) { this.operations = operations; }
    @Override public void process(ChatProcessingContext context) { operations.route(context); }
    @Override public String statusMessage() { return "正在进行意图路由与决策"; }
}
