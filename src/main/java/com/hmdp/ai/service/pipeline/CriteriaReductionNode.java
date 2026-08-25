package com.hmdp.ai.service.pipeline;

public class CriteriaReductionNode implements ChatPipelineNode {
    private final ChatPipelineOperations operations;
    public CriteriaReductionNode(ChatPipelineOperations operations) { this.operations = operations; }
    @Override public void process(ChatProcessingContext context) { operations.reduceCriteria(context); }
}
