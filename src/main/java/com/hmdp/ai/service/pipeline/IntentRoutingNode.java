package com.hmdp.ai.service.pipeline;

import com.hmdp.ai.dto.ChatStreamEventData;

public class IntentRoutingNode implements ChatPipelineNode {
    private final ChatPipelineOperations operations;
    public IntentRoutingNode(ChatPipelineOperations operations) { this.operations = operations; }
    @Override public void process(ChatProcessingContext context) { operations.route(context); }
    @Override public String statusMessage() { return "正在进行意图路由与决策"; }
    @Override public void enrichSuccessMetadata(ChatProcessingContext context, ChatStreamEventData event) {
        event.getMetadata().put("action", context.getAction());
        event.getMetadata().put("reason", context.getRoutingReason());
    }
}
