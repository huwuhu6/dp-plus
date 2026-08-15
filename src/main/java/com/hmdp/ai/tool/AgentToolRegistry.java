package com.hmdp.ai.tool;

import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentToolRegistry {
    @Resource private List<BaseAgentTool> tools;

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
}
