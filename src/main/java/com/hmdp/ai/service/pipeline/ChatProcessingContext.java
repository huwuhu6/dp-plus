package com.hmdp.ai.service.pipeline;

import com.hmdp.ai.dto.ChatMessageRequest;
import com.hmdp.ai.dto.ChatMessageResponse;
import com.hmdp.ai.dto.ContextRewriteResult;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.entity.AiChatSession;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Per-turn transient data. Durable business state stays in ConversationWorkingMemory. */
@Getter
@Setter
@RequiredArgsConstructor
public class ChatProcessingContext {
    private final ChatMessageRequest request;
    private final Consumer<String> textDeltaConsumer;
    private String originalMessage;
    private String effectiveMessage;
    private String chatId;
    private List<Map<String, Object>> chatHistory = Collections.emptyList();
    private AiChatSession chatSession;
    private Long activeDecisionSessionId;
    private DecisionResponse activeDecision;
    private ContextRewriteResult contextRewrite;
    private ChatProcessingAction action = ChatProcessingAction.NONE;
    private String route;
    private boolean cancelActiveDecision;
    private boolean usedModel;
    private ChatMessageResponse response;

    public boolean isCompleted() {
        return response != null;
    }
}
