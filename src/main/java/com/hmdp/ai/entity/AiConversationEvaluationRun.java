package com.hmdp.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("tbl_ai_conversation_evaluation_run")
public class AiConversationEvaluationRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String model;
    private String datasetVersion;
private String gitCommit;
    private Integer caseCount;
    private Integer routeMatchedCount;
    private Integer contextRewriteExpectedCount;
    private Integer contextRewriteMatchedCount;
    private Integer toolMatchedCount;
    private Integer toolExpectedCount;
    private Integer toolCoveredCount;
    private Integer localityMatchedCount;
    private Integer finalStatusMatchedCount;
    private Integer shopMatchedCount;
    private Integer unseenRecommendationExpectedCount;
    private Integer unseenRecommendationMatchedCount;
    private Integer completedCount;
    private Long avgDurationMs;
    private Long p50DurationMs;
    private Long p95DurationMs;
    private Long p99DurationMs;
    private Double errorRate;
    private Integer modelCallCount;
    private Integer modelSuccessCount;
    private Integer modelFailureCount;
    private Long promptTokenCount;
    private Long completionTokenCount;
    private String status;
    private String errorSummary;
}
