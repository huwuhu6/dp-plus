package com.hmdp.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai")
public class AiProperties {
    private String provider = "deepseek";
    private String baseUrl = "https://api.deepseek.com";
    private String apiKey;
    private String model = "deepseek-v4-flash";
    private Integer timeoutMs = 20000;
    private Boolean narrativeEnabled = true;
    private Integer toolPlanningTimeoutMs = 8000;
    private Integer toolExecutionTimeoutMs = 2500;
    private Integer answerPolishTimeoutMs = 8000;
    private ResultEvaluationProperties resultEvaluation = new ResultEvaluationProperties();
    private RoutingProperties routing = new RoutingProperties();
    private QueryRewriteProperties queryRewrite = new QueryRewriteProperties();
    private String retrievalStrategyVersion = "structured-profile-evidence-v2";
    private String evaluationDatasetVersion = "seed-v2";
    private String holdoutDatasetVersion = "holdout-v1";
    private String conversationEvaluationDatasetVersion = "conversation-v1";
    private String conversationHoldoutDatasetVersion = "conversation-holdout-v1";

    public boolean isConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    @Data
    public static class QueryRewriteProperties {
        private Boolean enabled = true;
        private String provider = "dashscope";
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String apiKey;
        private String model = "qwen-flash";
        private Integer timeoutMs = 8000;

        public boolean isConfigured() {
            return Boolean.TRUE.equals(enabled) && apiKey != null && !apiKey.trim().isEmpty();
        }
    }

    @Data
    public static class RoutingProperties {
        private Boolean enabled = true;
        private String provider = "dashscope";
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String apiKey;
        private String model = "qwen-flash";
        private Integer timeoutMs = 4000;

        public boolean isConfigured() {
            return Boolean.TRUE.equals(enabled) && apiKey != null && !apiKey.trim().isEmpty();
        }
    }

    @Data
    public static class ResultEvaluationProperties {
        private Boolean autoExpandDefaultNearbyRadius = true;
        private Double autoExpandedNearbyRadiusKm = 5D;
    }
}
