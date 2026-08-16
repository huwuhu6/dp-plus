package com.hmdp.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("ai_evaluation_case_result")
public class AiEvaluationCaseResult {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long caseId;
    private Long sessionId;
    private String initialStatus;
    private String actualStatus;
    private String recommendedShopIds;
    private Boolean statusMatched;
    private String finalStatus;
    private Boolean finalStatusMatched;
    private Boolean constraintMatched;
    private String constraintMismatch;
    private Double recallAtK;
    private Double reciprocalRank;
    private Boolean hardConstraintViolated;
    private Boolean factualConsistent;
    private Double evidenceCoverageRate;
    private Integer modelCallCount;
    private Integer modelSuccessCount;
    private Integer modelFailureCount;
    private Integer promptTokenCount;
    private Integer completionTokenCount;
    private Long totalDurationMs;
    private Long extractingDurationMs;
    private String errorMessage;
}
