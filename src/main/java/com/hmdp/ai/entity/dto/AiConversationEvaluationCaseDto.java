package com.hmdp.ai.entity.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * JSONL 原生反序列化 DTO，对应 src/main/resources/eval/datasets/{datasetVersion}.jsonl。
 * 复杂结构（turns/expectedRoutes/expectedMemory 等）保持原生嵌套，由 Loader 转换为实体的 JSON 字符串字段。
 */
@Data
public class AiConversationEvaluationCaseDto {
    private String caseCode;
    private String datasetVersion;
    private Boolean active;
    private List<Map<String, Object>> turns;
    private List<String> expectedRoutes;
    private List<Object> expectedContextRewrites;
    private List<String> expectedToolNames;
    private Object expectedToolArguments;
    private String expectedFinalStatus;
    private String expectedShopIds;
    private String expectedCity;
    private Integer expectedErrorCount;
    private List<String> expectedRecoveryRoutes;
    private Map<String, Object> expectedMemory;
    private Integer expectedUnseenFromTurn;
    private List<Object> expectedUnseenPairs;
    private String notes;
}
