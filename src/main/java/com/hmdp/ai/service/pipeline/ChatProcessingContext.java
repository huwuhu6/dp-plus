package com.hmdp.ai.service.pipeline;

import com.hmdp.ai.dto.ChatMessageRequest;
import com.hmdp.ai.dto.ChatMessageResponse;
import com.hmdp.ai.dto.ContextRewriteResult;
import com.hmdp.ai.dto.ConversationWorkingMemory;
import com.hmdp.ai.dto.CriteriaMergeResult;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.DecisionRequest;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.dto.PolicyDecision;
import com.hmdp.ai.entity.AiChatSession;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import com.hmdp.ai.dto.ChatStreamEventData;
import com.hmdp.ai.runtime.RoutingDecisionAssessment;

/** Per-turn transient data. Durable business state stays in ConversationWorkingMemory. */
@Getter
@Setter
@RequiredArgsConstructor
public class ChatProcessingContext {
    private final ChatMessageRequest request;
    private final Consumer<String> textDeltaConsumer;
    private Consumer<ChatStreamEventData> eventConsumer;
    private String originalMessage;
    private String effectiveMessage;
    private String chatId;
    private List<Map<String, Object>> chatHistory = Collections.emptyList();
    private AiChatSession chatSession;
    private ConversationWorkingMemory workingMemory;
    private Long activeDecisionSessionId;
    private DecisionResponse activeDecision;
    private ContextRewriteResult contextRewrite;
    private ChatProcessingAction action = ChatProcessingAction.NONE;
    private String route;
    private String routingReason;
    private boolean cancelActiveDecision;
    private boolean usedModel;
    private DecisionRequest decisionRequest;
    private DecisionConstraints mergedConstraints;
    private CriteriaMergeResult criteriaMergeResult;
    private PolicyDecision policyDecision;
    private RoutingDecisionAssessment routingAssessment;
    private ChatMessageResponse response;

    public boolean isCompleted() {
        return response != null;
    }

    public String getRewrittenQuery() {
        return contextRewrite == null ? null : contextRewrite.getRewrittenQuery();
    }

    public DecisionConstraints getSearchCriteria() {
        return mergedConstraints;
    }

    public void publishEvent(String eventName, ChatStreamEventData data) {
        if (eventConsumer == null || data == null) return;
        data.setEventName(eventName);
        try {
            eventConsumer.accept(data);
        } catch (RuntimeException ignored) {
            // Streaming telemetry must not affect the synchronous business pipeline.
        }
    }
}
