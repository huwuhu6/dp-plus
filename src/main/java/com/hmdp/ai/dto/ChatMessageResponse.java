package com.hmdp.ai.dto;

import lombok.Data;

import java.util.List;

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
    private DecisionContextQuery decisionContextQuery;
    private DecisionContextFacts decisionContextFacts;
    /** Experimental request-scoped IR; null in mode=off and never persisted. */
    private TurnSemanticIR structuredUnderstanding;
    private Boolean structuredUnderstandingValid;
    private Boolean structuredUnderstandingFallback;
    private List<String> structuredUnderstandingErrors;
    private String policyAction;
    private String policyReason;
    /** Business output may be valid even when a post-execution durable trace record failed. */
    private Boolean traceIncomplete;
}
