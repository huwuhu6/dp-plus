package com.hmdp.ai.dto;

import lombok.Data;

@Data
public class ChatMessageRequest {
    private String message;
    private String chatId;
    private Long decisionSessionId;
}
