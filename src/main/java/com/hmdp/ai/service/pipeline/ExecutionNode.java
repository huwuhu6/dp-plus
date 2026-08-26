package com.hmdp.ai.service.pipeline;

public class ExecutionNode implements ChatPipelineNode {
    private final ChatPipelineOperations operations;
    public ExecutionNode(ChatPipelineOperations operations) { this.operations = operations; }
    @Override public void process(ChatProcessingContext context) { operations.execute(context); }
    @Override public String statusMessage() { return "正在执行餐饮决策与业务查询"; }
}
