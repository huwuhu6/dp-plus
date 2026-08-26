package com.hmdp.ai.service.pipeline;

public class ContextRewriteNode implements ChatPipelineNode {
    private final ChatPipelineOperations operations;
    public ContextRewriteNode(ChatPipelineOperations operations) { this.operations = operations; }
    @Override public void process(ChatProcessingContext context) { operations.rewrite(context); }
    @Override public String statusMessage() { return "正在重写对话上下文"; }
}
