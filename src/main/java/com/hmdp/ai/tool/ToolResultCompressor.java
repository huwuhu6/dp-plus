package com.hmdp.ai.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Establishes the boundary between persistence/tool payloads and agent context.
 * Tool output is deliberately compact before it reaches audit storage or a model prompt.
 */
@Component
public class ToolResultCompressor {
    private static final int MAX_FACT_ENTRIES = 12;
    private static final int MAX_LIST_ITEMS = 6;
    private static final int MAX_TEXT_CHARS = 240;
    private static final int MAX_DISPLAY_CHARS = 1600;

    @Resource private ObjectMapper objectMapper;

    public AgentToolResult compress(AgentToolResult source) {
        if (source == null) return null;
        source.setSummary(limit(clean(source.getSummary()), 240));
        source.setDisplayText(limit(clean(source.getDisplayText()), MAX_DISPLAY_CHARS));
        Map<String, Object> normalized = objectMapper.convertValue(source.getFacts(), new TypeReference<Map<String, Object>>() { });
        source.setFacts(compactMap(normalized, 0));
        return source;
    }

    private Map<String, Object> compactMap(Map<String, Object> source, int depth) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (source == null || depth > 3) return result;
        int count = 0;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (count++ >= MAX_FACT_ENTRIES) break;
            result.put(entry.getKey(), compactValue(entry.getValue(), depth + 1));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object compactValue(Object value, int depth) {
        if (value == null) return null;
        if (value instanceof String) return limit(clean((String) value), MAX_TEXT_CHARS);
        if (value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Map) return compactMap((Map<String, Object>) value, depth);
        if (value instanceof List) {
            List<Object> result = new ArrayList<Object>();
            int count = 0;
            for (Object item : (List<?>) value) {
                if (count++ >= MAX_LIST_ITEMS) break;
                result.add(compactValue(item, depth + 1));
            }
            return result;
        }
        return limit(clean(String.valueOf(value)), MAX_TEXT_CHARS);
    }

    private String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String limit(String value, int maxChars) {
        if (value.length() <= maxChars) return value;
        if (maxChars <= 3) return value.substring(0, maxChars);
        return value.substring(0, maxChars - 3) + "...";
    }
}
