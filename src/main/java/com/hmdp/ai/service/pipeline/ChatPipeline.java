package com.hmdp.ai.service.pipeline;

import java.util.List;

/** Sequential pipeline with explicit early termination for clarification branches. */
public class ChatPipeline {
    private final List<ChatPipelineNode> nodes;

    public ChatPipeline(List<ChatPipelineNode> nodes) {
        this.nodes = nodes;
    }

    public void process(ChatProcessingContext context) {
        for (ChatPipelineNode node : nodes) {
            if (context.isCompleted()) return;
            node.process(context);
        }
    }
}
