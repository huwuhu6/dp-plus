package com.hmdp.ai.tool;

import lombok.Data;

@Data
public class ToolExecutionResult {
    private Integer order;
    private String toolName;
    private String effectiveArguments;
    private AgentToolResult result;
    private String errorMessage;
    private Long durationMs;

    public boolean isSuccess() {
        return result != null && errorMessage == null;
    }
}
