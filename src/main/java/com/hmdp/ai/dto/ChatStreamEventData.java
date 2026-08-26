package com.hmdp.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** Payload shared by all SSE event frames. */
@Data
public class ChatStreamEventData {
    @JsonIgnore
    private String eventName;
    private String stage;
    private String status;
    private String message;
    private String delta;
    private String componentType;
    private String toolName;
    private String arguments;
    private Long durationMs;
    private Object output;
    private Object payload;
    private ChatMessageResponse response;
    private Map<String, Object> metadata = new LinkedHashMap<String, Object>();
}
