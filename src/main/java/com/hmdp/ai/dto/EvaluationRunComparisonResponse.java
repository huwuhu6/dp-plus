package com.hmdp.ai.dto;

import com.hmdp.ai.entity.AiEvaluationRun;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class EvaluationRunComparisonResponse {
    private AiEvaluationRun baselineRun;
    private AiEvaluationRun currentRun;
    private Boolean retrievalStrategyChanged;
    private Map<String, Double> metricDeltas = new LinkedHashMap<>();
}
