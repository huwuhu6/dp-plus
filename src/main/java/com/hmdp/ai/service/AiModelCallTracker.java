package com.hmdp.ai.service;

import com.hmdp.ai.dto.DecisionMetrics;
import org.springframework.stereotype.Component;

@Component
public class AiModelCallTracker {
    private final ThreadLocal<DecisionMetrics> metricsHolder = new ThreadLocal<>();

    public void start(DecisionMetrics metrics) {
        metricsHolder.set(metrics);
    }

    public void recordSuccess() {
        recordSuccess(null, null);
    }

    public void recordSuccess(Integer promptTokens, Integer completionTokens) {
        DecisionMetrics metrics = metricsHolder.get();
        if (metrics == null) return;
        metrics.setModelCallCount(metrics.getModelCallCount() + 1);
        metrics.setModelSuccessCount(metrics.getModelSuccessCount() + 1);
        metrics.setPromptTokenCount(metrics.getPromptTokenCount() + value(promptTokens));
        metrics.setCompletionTokenCount(metrics.getCompletionTokenCount() + value(completionTokens));
    }

    public void recordFailure() {
        DecisionMetrics metrics = metricsHolder.get();
        if (metrics == null) return;
        metrics.setModelCallCount(metrics.getModelCallCount() + 1);
        metrics.setModelFailureCount(metrics.getModelFailureCount() + 1);
    }

    public void clear() {
        metricsHolder.remove();
    }

    private int value(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
