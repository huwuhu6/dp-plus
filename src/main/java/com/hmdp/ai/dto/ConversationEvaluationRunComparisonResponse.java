package com.hmdp.ai.dto;

import com.hmdp.ai.entity.AiConversationEvaluationRun;
import lombok.Data;

import java.util.Map;

@Data
public class ConversationEvaluationRunComparisonResponse {
    private AiConversationEvaluationRun baselineRun;
    private AiConversationEvaluationRun currentRun;
    private Map<String, Double> metricDeltas;
}
