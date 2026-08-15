package com.hmdp.ai.dto;

import lombok.Data;

@Data
public class DecisionTraceItem {
    private String state;
    private String summary;
    private Long durationMs;

    public DecisionTraceItem(String state, String summary, Long durationMs) {
        this.state = state;
        this.summary = summary;
        this.durationMs = durationMs;
    }
}
