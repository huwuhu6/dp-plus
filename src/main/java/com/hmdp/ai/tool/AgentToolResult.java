package com.hmdp.ai.tool;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AgentToolResult {
    private String toolName;
    private String summary;
    private String displayText;
    private Long durationMs;
    private Long focusedShopId;
    private String focusedShopName;
    private ToolStateDelta stateDelta;
    private Map<String, Object> facts = new LinkedHashMap<String, Object>();

    public AgentToolResult summary(String value) {
        this.summary = value;
        return this;
    }

    public AgentToolResult displayText(String value) {
        this.displayText = value;
        return this;
    }
}
