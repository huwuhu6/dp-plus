package com.hmdp.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.service.AiModelCallTracker;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpStatusCodeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiCompatibleClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);
    @Resource
    private AiProperties aiProperties;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private AiModelCallTracker modelCallTracker;

    public JsonNode chatCompletion(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                                   Map<String, Object> toolChoice) {
        return chatCompletion(messages, tools, toolChoice, "CHAT_COMPLETION");
    }

    public JsonNode chatCompletion(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                                   Map<String, Object> toolChoice, String action) {
        if (!aiProperties.isConfigured()) {
            throw new IllegalStateException("未配置 DEEPSEEK_API_KEY，当前只能使用本地规则解析");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", aiProperties.getModel());
        body.put("messages", messages);
        body.put("temperature", 0.1);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }
        if (toolChoice != null) {
            body.put("tool_choice", toolChoice);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(aiProperties.getApiKey());
        String url = trimTrailingSlash(aiProperties.getBaseUrl()) + "/chat/completions";
        long startedAt = System.currentTimeMillis();
        log.info("[AI][model] action={} model={} tools={} event=REQUEST", action, aiProperties.getModel(),
                tools == null ? 0 : tools.size());
        try {
            ResponseEntity<JsonNode> response = restTemplate().postForEntity(
                    url, new HttpEntity<>(body, headers), JsonNode.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("模型服务未返回有效响应");
            }
            modelCallTracker.recordSuccess();
            log.info("[AI][model] action={} model={} event=SUCCESS durationMs={}", action,
                    aiProperties.getModel(), System.currentTimeMillis() - startedAt);
            return response.getBody();
        } catch (HttpStatusCodeException e) {
            modelCallTracker.recordFailure();
            log.warn("[AI][model] action={} model={} event=FAILURE durationMs={} status={} detail={}", action,
                    aiProperties.getModel(), System.currentTimeMillis() - startedAt, e.getRawStatusCode(),
                    compactError(e.getResponseBodyAsString()));
            throw e;
        } catch (RuntimeException e) {
            modelCallTracker.recordFailure();
            log.warn("[AI][model] action={} model={} event=FAILURE durationMs={} errorType={}", action,
                    aiProperties.getModel(), System.currentTimeMillis() - startedAt, e.getClass().getSimpleName());
            throw e;
        }
    }

    public String chatText(List<Map<String, Object>> messages) {
        return chatText(messages, "NARRATIVE_GENERATION");
    }

    public String chatText(List<Map<String, Object>> messages, String action) {
        JsonNode response = chatCompletion(messages, null, null, action);
        JsonNode content = response.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("模型未生成最终答案");
        }
        return content.asText();
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private RestTemplate restTemplate() {
        int timeoutMs = aiProperties.getTimeoutMs() == null ? 20000 : aiProperties.getTimeoutMs();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
    }

    private String compactError(String error) {
        if (error == null) return "";
        String compact = error.replaceAll("[\\r\\n\\t]+", " ");
        return compact.length() > 500 ? compact.substring(0, 500) : compact;
    }
}
