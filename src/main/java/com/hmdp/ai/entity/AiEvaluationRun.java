package com.hmdp.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("tbl_ai_evaluation_run")
public class AiEvaluationRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String model;
    private String retrievalStrategyVersion;
    private String evaluationDatasetVersion;
    private Integer caseCount;
    private Integer rankingEvaluatedCount;
    private Integer followUpEvaluatedCount;
    private Integer followUpStatusMatchedCount;
    private Integer statusMatchedCount;
    private Integer constraintMatchedCount;
    private Integer completedCount;
    private Integer modelCallCount;
    private Integer modelSuccessCount;
    private Integer modelFailureCount;
    private Integer promptTokenCount;
    private Integer completionTokenCount;
    private Long avgTotalDurationMs;
    private Long p95TotalDurationMs;
    private Long avgExtractingDurationMs;
    private Integer hardConstraintViolationCount;
    private Integer factualConsistentCount;
    private Double recallAtK;
    private Double mrr;
    private Double evidenceCoverageRate;
    private String status;
    private String errorSummary;
}
