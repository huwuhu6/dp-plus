package com.hmdp.ai.dto;

import lombok.Data;

@Data
public class DecisionMetrics {
    private Long totalDurationMs;
    private Long extractingDurationMs;
    private Long retrievingDurationMs;
    private Long rerankingDurationMs;
    private Long semanticRetrievingDurationMs;
    private Long answeringDurationMs;
    private Integer modelCallCount = 0;
    private Integer modelSuccessCount = 0;
    private Integer modelFailureCount = 0;
    private Integer promptTokenCount = 0;
    private Integer completionTokenCount = 0;
    private Integer initialCandidateCount = 0;
    private Integer hardMatchedCandidateCount = 0;
    private Integer finalCandidateCount = 0;
    private Integer strictCandidateCount = 0;
    private Integer relaxationCount = 0;
    private Boolean automaticRelaxationApplied = false;
    private String resultEvaluationOutcome = "UNKNOWN";
    private Integer evidenceCoveredCandidateCount = 0;
    private Double evidenceCoverageRate = 0D;
    private Boolean factualConsistent = true;
    private Boolean narrativeRejected = false;
    private Boolean semanticRetrievalUsed = false;
}
