package com.hmdp.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.AgentSessionContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpringAiAgentToolCallbackTest {
    @Test
    void exposesSchemaAndPreservesBusinessToolResult() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        BaseAgentTool tool = new BaseAgentTool() {
            @Override public String name() { return "test_tool"; }
            @Override public String description() { return "test tool"; }
            @Override public Map<String, Object> parameterSchema() {
                Map<String, Object> properties = new LinkedHashMap<String, Object>();
                properties.put("shopId", property("integer", "shop id"));
                return objectSchema(properties, "shopId");
            }
            @Override public AgentToolResult execute(Map<String, Object> input, AgentSessionContext context) {
                return new AgentToolResult().summary("found").displayText("shop detail");
            }
        };

        SpringAiAgentToolCallback callback = new SpringAiAgentToolCallback(tool, new AgentSessionContext(), objectMapper);

        assertEquals("test_tool", callback.getToolDefinition().name());
        JsonNode response = objectMapper.readTree(callback.call("{\"shopId\":8}"));
        assertEquals("found", response.path("summary").asText());
        assertEquals("shop detail", response.path("displayText").asText());
    }
}
