package com.hmdp.ai.service.pipeline;

import com.hmdp.ai.dto.ChatStreamEventData;

public class ContextRewriteNode implements ChatPipelineNode {
    private final ChatPipelineOperations operations;
    public ContextRewriteNode(ChatPipelineOperations operations) { this.operations = operations; }
    @Override public void process(ChatProcessingContext context) { operations.rewrite(context); }
    @Override public String statusMessage() { return "正在重写对话上下文"; }
    @Override public void enrichSuccessMetadata(ChatProcessingContext context, ChatStreamEventData event) {
        event.getMetadata().put("rewrittenQuery", context.getRewrittenQuery());
    }
}
