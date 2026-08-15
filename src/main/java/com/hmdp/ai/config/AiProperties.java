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
    private Boolean narrativeEnabled = false;
    private String retrievalStrategyVersion = "structured-profile-evidence-v2";
    private String evaluationDatasetVersion = "seed-v2";
    private String holdoutDatasetVersion = "holdout-v1";

    public boolean isConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }
}
