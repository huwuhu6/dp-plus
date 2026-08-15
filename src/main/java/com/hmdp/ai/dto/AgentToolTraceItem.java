package com.hmdp.ai.dto;

import lombok.Data;

@Data
public class AgentToolTraceItem {
    private String toolName;
    private String summary;
    private Long durationMs;

    public AgentToolTraceItem(String toolName, String summary, Long durationMs) {
        this.toolName = toolName;
        this.summary = summary;
        this.durationMs = durationMs;
    }
}
