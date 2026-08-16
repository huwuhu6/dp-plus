package com.hmdp.ai.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.AgentSessionContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.Map;

/** Adapts the existing business-tool contract to Spring AI without dropping session context. */
public class SpringAiAgentToolCallback implements ToolCallback {
    private final BaseAgentTool tool;
    private final AgentSessionContext sessionContext;
    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    public SpringAiAgentToolCallback(BaseAgentTool tool, AgentSessionContext sessionContext, ObjectMapper objectMapper) {
        this.tool = tool;
        this.sessionContext = sessionContext;
        this.objectMapper = objectMapper;
        try {
            this.definition = DefaultToolDefinition.builder()
                    .name(tool.name())
                    .description(tool.description())
                    .inputSchema(objectMapper.writeValueAsString(tool.parameterSchema()))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to build Spring AI tool definition: " + tool.name(), e);
        }
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        return invoke(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext ignored) {
        return invoke(toolInput);
    }

    private String invoke(String toolInput) {
        try {
            Map<String, Object> input = objectMapper.readValue(toolInput == null || toolInput.isBlank() ? "{}" : toolInput,
                    new TypeReference<Map<String, Object>>() { });
            AgentToolResult result = tool.execute(input, sessionContext);
            Map<String, Object> response = new LinkedHashMap<String, Object>();
            response.put("summary", result.getSummary());
            response.put("displayText", result.getDisplayText());
            response.put("focusedShopId", result.getFocusedShopId());
            response.put("focusedShopName", result.getFocusedShopName());
            response.put("facts", result.getFacts());
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalArgumentException("Spring AI tool invocation failed: " + tool.name(), e);
        }
    }
}
