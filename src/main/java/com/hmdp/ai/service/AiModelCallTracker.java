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
        DecisionMetrics metrics = metricsHolder.get();
        if (metrics == null) return;
        metrics.setModelCallCount(metrics.getModelCallCount() + 1);
        metrics.setModelSuccessCount(metrics.getModelSuccessCount() + 1);
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
}
