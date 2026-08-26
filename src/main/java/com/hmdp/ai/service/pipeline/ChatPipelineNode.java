package com.hmdp.ai.service.pipeline;

/** A single deterministic stage in the synchronous chat request pipeline. */
public interface ChatPipelineNode {
    void process(ChatProcessingContext context);
}
