package com.hmdp.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("tbl_ai_decision_metric")
public class AiDecisionMetric {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Integer attemptNo;
    private Long totalDurationMs;
    private Long extractingDurationMs;
    private Long retrievingDurationMs;
    private Long rerankingDurationMs;
    private Long answeringDurationMs;
    private Integer modelCallCount;
    private Integer modelSuccessCount;
    private Integer modelFailureCount;
    private Integer promptTokenCount;
    private Integer completionTokenCount;
    private Integer initialCandidateCount;
    private Integer hardMatchedCandidateCount;
    private Integer finalCandidateCount;
    private Integer strictCandidateCount;
    private Integer relaxationCount;
    private Boolean automaticRelaxationApplied;
    private String resultEvaluationOutcome;
    private Integer evidenceCoveredCandidateCount;
    private Double evidenceCoverageRate;
    private Boolean factualConsistent;
    private Boolean narrativeRejected;
}
