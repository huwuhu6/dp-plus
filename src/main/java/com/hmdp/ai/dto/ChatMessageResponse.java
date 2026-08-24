package com.hmdp.ai.dto;

import lombok.Data;

@Data
public class ChatMessageResponse {
    private String chatId;
    private String route;
    private String answer;
    private Long decisionSessionId;
    private String decisionStatus;
    private DecisionResponse decision;
    private AgentConversationResponse conversation;
    private Boolean usedModel;
    private String degradedReason;
    private ContextRewriteResult contextRewrite;
}
