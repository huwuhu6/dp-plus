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
        private Integer expectedErrorCount;
        private String expectedRecoveryRoutesJson;
        private String expectedMemoryJson;
        private Integer expectedUnseenFromTurn;
        private String expectedUnseenPairsJson;
        private String actualRoutesJson;
        private String actualContextRewritesJson;
        private String actualToolNamesJson;
        private String actualToolCallsJson;
        private String actualRecommendationSnapshotsJson;
        private String actualFinalStatus;
        private String recommendedShopIds;
        private Boolean routeMatched;
        private Boolean contextRewriteMatched;
        private Boolean toolMatched;
        private Boolean toolArgumentsMatched;
        private Boolean localityMatched;
        private Boolean finalStatusMatched;
        private Boolean shopMatched;
        private Integer actualErrorCount;
        private Boolean recoveryMatched;
        private Boolean memoryMatched;
        private Boolean unseenRecommendationsMatched;
        /** Structured per-turn assertion failures read from the persisted turn trace. */
        private List<Map<String, Object>> turnAssertionFailures;
        private Long durationMs;
        private String errorMessage;
    }
}
