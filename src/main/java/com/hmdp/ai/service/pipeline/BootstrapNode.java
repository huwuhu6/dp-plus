package com.hmdp.ai.service.pipeline;

public class BootstrapNode implements ChatPipelineNode {
    private final ChatPipelineOperations operations;
    public BootstrapNode(ChatPipelineOperations operations) { this.operations = operations; }
    @Override public void process(ChatProcessingContext context) { operations.bootstrap(context); }
}
