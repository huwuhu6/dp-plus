package com.hmdp.ai.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.AgentSessionContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentToolRegistry {
    @Resource private List<BaseAgentTool> tools;
    @Resource private ObjectMapper objectMapper;

    public List<Map<String, Object>> definitions() {
        List<Map<String, Object>> definitions = new ArrayList<Map<String, Object>>();
        for (BaseAgentTool tool : tools) definitions.add(tool.definition());
        return definitions;
    }

    public BaseAgentTool find(String name) {
        for (BaseAgentTool tool : tools) if (tool.name().equals(name)) return tool;
        throw new IllegalArgumentException("不支持的 Agent 工具: " + name);
    }

    public Map<String, String> descriptions() {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (BaseAgentTool tool : tools) result.put(tool.name(), tool.description());
        return result;
    }

    public List<ToolCallback> springAiCallbacks(AgentSessionContext context) {
        List<ToolCallback> callbacks = new ArrayList<ToolCallback>();
        for (BaseAgentTool tool : tools) {
            callbacks.add(new SpringAiAgentToolCallback(tool, context, objectMapper));
        }
        return callbacks;
    }
}
