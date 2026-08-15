package com.hmdp.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DecisionResponse {
    private Long sessionId;
    private String status;
    private String answer;
    private String question;
    private List<DecisionOption> options = new ArrayList<>();
    private DecisionConstraints constraints;
    private List<DecisionRecommendation> recommendations = new ArrayList<>();
    private List<DecisionTraceItem> trace = new ArrayList<>();
    private DecisionMetrics metrics;
    private Boolean usedModel;
    private String degradedReason;
}
