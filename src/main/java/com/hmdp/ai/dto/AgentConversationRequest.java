package com.hmdp.ai.dto;

import lombok.Data;

@Data
public class AgentConversationRequest {
    /** Required by the standalone follow-up endpoint to bind the request to chat Working Memory. */
    private String chatId;
    private String message;
}
