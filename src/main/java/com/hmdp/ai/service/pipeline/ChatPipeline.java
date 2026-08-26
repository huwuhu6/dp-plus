package com.hmdp.ai.service.pipeline;

import com.hmdp.ai.dto.ChatStreamEventData;
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
            publishNodeStatus(context, node, "running");
            node.process(context);
            publishNodeStatus(context, node, "success");
        }
    }

    private void publishNodeStatus(ChatProcessingContext context, ChatPipelineNode node, String stage) {
        ChatStreamEventData event = new ChatStreamEventData();
        event.setStage(stage);
        event.setStatus(stage);
        event.setMessage(node.statusMessage());
        event.getMetadata().put("node", node.getClass().getSimpleName());
        context.publishEvent("node_status", event);
    }
}
