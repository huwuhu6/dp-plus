package com.hmdp.ai.service.pipeline;

import com.hmdp.ai.dto.ChatStreamEventData;

public class CriteriaReductionNode implements ChatPipelineNode {
    private final ChatPipelineOperations operations;
    public CriteriaReductionNode(ChatPipelineOperations operations) { this.operations = operations; }
    @Override public void process(ChatProcessingContext context) { operations.reduceCriteria(context); }
    @Override public String statusMessage() { return "正在归纳本轮搜索条件"; }
    @Override public void enrichSuccessMetadata(ChatProcessingContext context, ChatStreamEventData event) {
        event.getMetadata().put("criteria", context.getSearchCriteria());
    }
}
