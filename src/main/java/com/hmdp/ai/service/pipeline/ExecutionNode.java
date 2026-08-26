package com.hmdp.ai.service.pipeline;

import com.hmdp.ai.dto.ChatStreamEventData;

public class ExecutionNode implements ChatPipelineNode {
    private final ChatPipelineOperations operations;
    public ExecutionNode(ChatPipelineOperations operations) { this.operations = operations; }
    @Override public void process(ChatProcessingContext context) { operations.execute(context); }
    @Override public String statusMessage() { return "正在执行餐饮决策与业务查询"; }
    @Override public void enrichSuccessMetadata(ChatProcessingContext context, ChatStreamEventData event) {
        event.getMetadata().put("executionMode", context.getAction());
    }
}
