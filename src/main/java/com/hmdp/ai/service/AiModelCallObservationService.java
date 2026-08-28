package com.hmdp.ai.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluation-only per-turn model-call trace. Unlike ChatMessageResponse.usedModel,
 * entries represent calls that actually reached a client.
 */
@Component
public class AiModelCallObservationService {
    private final ThreadLocal<List<Map<String, Object>>> calls = new ThreadLocal<List<Map<String, Object>>>();

    public void begin() {
        calls.set(new ArrayList<Map<String, Object>>());
    }

    public void record(String purpose, boolean success, long durationMs, Integer promptTokens, Integer completionTokens) {
        List<Map<String, Object>> current = calls.get();
        if (current == null) return;
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("purpose", purpose);
        item.put("success", success);
        item.put("durationMs", durationMs);
        item.put("promptTokens", value(promptTokens));
        item.put("completionTokens", value(completionTokens));
        current.add(item);
    }

    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> current = calls.get();
        if (current == null) return Collections.emptyList();
        return new ArrayList<Map<String, Object>>(current);
    }

    public void clear() {
        calls.remove();
    }

    private int value(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
