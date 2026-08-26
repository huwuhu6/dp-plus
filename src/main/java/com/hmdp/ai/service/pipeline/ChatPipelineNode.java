package com.hmdp.ai.service.pipeline;

import com.hmdp.ai.dto.ChatStreamEventData;

/** A single deterministic stage in the synchronous chat request pipeline. */
public interface ChatPipelineNode {
    void process(ChatProcessingContext context);

    default String statusMessage() {
        return getClass().getSimpleName();
    }

    default void enrichSuccessMetadata(ChatProcessingContext context, ChatStreamEventData event) {
    }
}
