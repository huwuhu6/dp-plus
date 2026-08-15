package com.hmdp.ai.dto;

import lombok.Data;

@Data
public class ChatMessageRequest {
    private String message;
    private Long decisionSessionId;
}
