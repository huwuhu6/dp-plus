package com.hmdp.ai.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** Payload shared by all SSE event frames. */
@Data
public class ChatStreamEventData {
    private String stage;
    private String message;
    private String delta;
    private String componentType;
    private Object payload;
    private ChatMessageResponse response;
    private Map<String, Object> metadata = new LinkedHashMap<String, Object>();
}
