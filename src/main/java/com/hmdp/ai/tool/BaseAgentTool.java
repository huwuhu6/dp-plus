package com.hmdp.ai.tool;

import com.hmdp.ai.dto.AgentSessionContext;

import java.util.LinkedHashMap;
import java.util.Map;

public abstract class BaseAgentTool {
    public ToolExecutionMode executionMode() { return ToolExecutionMode.PARALLEL_SAFE; }

    public abstract String name();

    public abstract String description();

    public abstract Map<String, Object> parameterSchema();

    public abstract AgentToolResult execute(Map<String, Object> input, AgentSessionContext context);

    public final Map<String, Object> definition() {
        Map<String, Object> function = new LinkedHashMap<String, Object>();
        function.put("name", name());
        function.put("description", description());
        function.put("parameters", parameterSchema());
        Map<String, Object> definition = new LinkedHashMap<String, Object>();
        definition.put("type", "function");
        definition.put("function", function);
        return definition;
    }

    protected Map<String, Object> objectSchema(Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", java.util.Arrays.asList(required));
        schema.put("additionalProperties", false);
        return schema;
    }

    protected Map<String, Object> property(String type, String description) {
        Map<String, Object> property = new LinkedHashMap<String, Object>();
        property.put("type", type);
        property.put("description", description);
        return property;
    }

    protected Long shopId(Map<String, Object> input, AgentSessionContext context) {
        Object value = input.get("shopId");
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String && !((String) value).trim().isEmpty()) return Long.valueOf((String) value);
        return context.getFocusedShopId();
    }
}
