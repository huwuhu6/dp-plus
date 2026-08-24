package com.hmdp.ai.dto;

import com.hmdp.ai.entity.AiConversationEvaluationRun;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ConversationEvaluationDiagnosticsResponse {
    private AiConversationEvaluationRun run;
    private Map<String, Integer> failureCounts;
    private List<CaseDiagnostic> failures;

    @Data
    public static class CaseDiagnostic {
        private Long caseId;
        private String caseCode;
        private String notes;
        private String expectedRoutesJson;
        private String expectedContextRewritesJson;
        private String expectedToolNamesJson;
        private String expectedToolArgumentsJson;
        private String expectedFinalStatus;
        private String expectedCity;
        private String actualRoutesJson;
        private String actualContextRewritesJson;
        private String actualToolNamesJson;
        private String actualToolCallsJson;
        private String actualFinalStatus;
        private String recommendedShopIds;
        private Boolean routeMatched;
        private Boolean contextRewriteMatched;
        private Boolean toolMatched;
        private Boolean toolArgumentsMatched;
        private Boolean localityMatched;
        private Boolean finalStatusMatched;
        private Boolean shopMatched;
        private Long durationMs;
        private String errorMessage;
    }
}
