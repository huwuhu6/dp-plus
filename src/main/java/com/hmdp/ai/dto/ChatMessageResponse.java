package com.hmdp.ai.dto;

import lombok.Data;

@Data
public class ChatMessageResponse {
    private String chatId;
    private String route;
    private String answer;
    private Long decisionSessionId;
    private DecisionResponse decision;
    private AgentConversationResponse conversation;
    private Boolean usedModel;
    private String degradedReason;
}
