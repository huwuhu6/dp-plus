package com.hmdp.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("ai_conversation_evaluation_case_result")
public class AiConversationEvaluationCaseResult {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long caseId;
    private String chatId;
    private String actualRoutesJson;
    private String actualToolNamesJson;
    private String actualToolCallsJson;
    private Integer expectedToolCount;
    private Integer coveredToolCount;
    private Integer unexpectedToolCount;
    private String actualFinalStatus;
    private String recommendedShopIds;
    private Boolean routeMatched;
    private Boolean toolMatched;
    private Boolean toolArgumentsMatched;
    private Boolean localityMatched;
    private Boolean finalStatusMatched;
    private Boolean shopMatched;
    private Long durationMs;
    private String turnOutputsJson;
    private String errorMessage;
}
