package com.hmdp.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.service.AiModelCallTracker;
import com.hmdp.ai.service.AiModelCallObservationService;
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

import jakarta.annotation.Resource;
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
    @Resource
    private AiModelCallObservationService modelCallObservationService;

    public JsonNode chatCompletion(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                                   Map<String, Object> toolChoice) {
        return chatCompletion(messages, tools, toolChoice, "CHAT_COMPLETION");
    }

    public JsonNode chatCompletion(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                                   Map<String, Object> toolChoice, String action) {
        return chatCompletion(messages, tools, toolChoice, action, null);
    }

    public JsonNode chatCompletion(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                                   Map<String, Object> toolChoice, String action, Integer timeoutMs) {
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
        log.info("[AI][model] action={} model={} tools={} timeoutMs={} query={} event=REQUEST", action,
                aiProperties.getModel(), tools == null ? 0 : tools.size(), resolvedTimeout(timeoutMs),
                requestSummary(action, messages));
        try {
            ResponseEntity<JsonNode> response = restTemplate(timeoutMs).postForEntity(
                    url, new HttpEntity<>(body, headers), JsonNode.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("模型服务未返回有效响应");
            }
            modelCallTracker.recordSuccess(response.getBody().path("usage").path("prompt_tokens").isNumber()
                            ? response.getBody().path("usage").path("prompt_tokens").asInt() : null,
                    response.getBody().path("usage").path("completion_tokens").isNumber()
                            ? response.getBody().path("usage").path("completion_tokens").asInt() : null);
            modelCallObservationService.record(observationPurpose(action), true, System.currentTimeMillis() - startedAt,
                    response.getBody().path("usage").path("prompt_tokens").isNumber() ? response.getBody().path("usage").path("prompt_tokens").asInt() : null,
                    response.getBody().path("usage").path("completion_tokens").isNumber() ? response.getBody().path("usage").path("completion_tokens").asInt() : null);
            log.info("[AI][model] action={} model={} event=SUCCESS durationMs={} toolCalls={}", action,
                    aiProperties.getModel(), System.currentTimeMillis() - startedAt,
                    compact(response.getBody().path("choices").path(0).path("message").path("tool_calls").toString()));
            return response.getBody();
        } catch (HttpStatusCodeException e) {
            modelCallTracker.recordFailure();
            modelCallObservationService.record(observationPurpose(action), false, System.currentTimeMillis() - startedAt, null, null);
            log.warn("[AI][model] action={} model={} event=FAILURE durationMs={} status={} detail={}", action,
                    aiProperties.getModel(), System.currentTimeMillis() - startedAt, e.getRawStatusCode(),
                    compactError(e.getResponseBodyAsString()));
            throw e;
        } catch (RuntimeException e) {
            modelCallTracker.recordFailure();
            modelCallObservationService.record(observationPurpose(action), false, System.currentTimeMillis() - startedAt, null, null);
            log.warn("[AI][model] action={} model={} event=FAILURE durationMs={} errorType={} detail={}", action,
                    aiProperties.getModel(), System.currentTimeMillis() - startedAt, e.getClass().getSimpleName(),
                    compactError(e.getMessage()));
            throw e;
        }
    }

    /** Uses the dedicated low-latency model for route classification, not the main agent model. */
    public JsonNode chatRoutingCompletion(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                                          Map<String, Object> toolChoice) {
        AiProperties.RoutingProperties routing = aiProperties.getRouting();
        if (routing == null || !routing.isConfigured()) {
            throw new IllegalStateException("Chat routing model is not configured");
        }
        return chatCompletion(messages, tools, toolChoice, "CHAT_ROUTING", routing.getTimeoutMs(),
                routing.getBaseUrl(), routing.getApiKey(), routing.getModel());
    }

    private JsonNode chatCompletion(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                                    Map<String, Object> toolChoice, String action, Integer timeoutMs,
                                    String baseUrl, String apiKey, String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", 0.1);
        if (tools != null && !tools.isEmpty()) body.put("tools", tools);
        if (toolChoice != null) body.put("tool_choice", toolChoice);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        String url = trimTrailingSlash(baseUrl) + "/chat/completions";
        long startedAt = System.currentTimeMillis();
        log.info("[AI][model] action={} model={} tools={} timeoutMs={} query={} event=REQUEST", action,
                model, tools == null ? 0 : tools.size(), resolvedTimeout(timeoutMs), requestSummary(action, messages));
        try {
            ResponseEntity<JsonNode> response = restTemplate(timeoutMs).postForEntity(
                    url, new HttpEntity<>(body, headers), JsonNode.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("模型服务未返回有效响应");
            }
            modelCallTracker.recordSuccess(response.getBody().path("usage").path("prompt_tokens").isNumber()
                            ? response.getBody().path("usage").path("prompt_tokens").asInt() : null,
                    response.getBody().path("usage").path("completion_tokens").isNumber()
                            ? response.getBody().path("usage").path("completion_tokens").asInt() : null);
            modelCallObservationService.record(observationPurpose(action), true, System.currentTimeMillis() - startedAt,
                    response.getBody().path("usage").path("prompt_tokens").isNumber() ? response.getBody().path("usage").path("prompt_tokens").asInt() : null,
                    response.getBody().path("usage").path("completion_tokens").isNumber() ? response.getBody().path("usage").path("completion_tokens").asInt() : null);
            log.info("[AI][model] action={} model={} event=SUCCESS durationMs={} toolCalls={}", action,
                    model, System.currentTimeMillis() - startedAt,
                    compact(response.getBody().path("choices").path(0).path("message").path("tool_calls").toString()));
            return response.getBody();
        } catch (HttpStatusCodeException e) {
            modelCallTracker.recordFailure();
            modelCallObservationService.record(observationPurpose(action), false, System.currentTimeMillis() - startedAt, null, null);
            log.warn("[AI][model] action={} model={} event=FAILURE durationMs={} status={} detail={}", action,
                    model, System.currentTimeMillis() - startedAt, e.getRawStatusCode(), compactError(e.getResponseBodyAsString()));
            throw e;
        } catch (RuntimeException e) {
            modelCallTracker.recordFailure();
            modelCallObservationService.record(observationPurpose(action), false, System.currentTimeMillis() - startedAt, null, null);
            log.warn("[AI][model] action={} model={} event=FAILURE durationMs={} errorType={} detail={}", action,
                    model, System.currentTimeMillis() - startedAt, e.getClass().getSimpleName(), compactError(e.getMessage()));
            throw e;
        }
    }

    public String chatText(List<Map<String, Object>> messages) {
        return chatText(messages, "NARRATIVE_GENERATION");
    }

    public String chatText(List<Map<String, Object>> messages, String action) {
        return chatText(messages, action, null);
    }

    public String chatText(List<Map<String, Object>> messages, String action, Integer timeoutMs) {
        JsonNode response = chatCompletion(messages, null, null, action, timeoutMs);
        JsonNode content = response.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("模型未生成最终答案");
        }
        String text = content.asText();
        log.info("[AI][model] action={} event=TEXT_RESULT chars={}", action, text.length());
        return text;
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String observationPurpose(String action) {
        return "CHAT_ROUTING".equals(action) ? "ROUTING" : "OTHER";
    }

    private RestTemplate restTemplate(Integer requestedTimeoutMs) {
        int timeoutMs = resolvedTimeout(requestedTimeoutMs);
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

    private int resolvedTimeout(Integer requestedTimeoutMs) {
        return requestedTimeoutMs == null ? (aiProperties.getTimeoutMs() == null ? 20000 : aiProperties.getTimeoutMs())
                : requestedTimeoutMs;
    }

    private String lastUserMessage(List<Map<String, Object>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Object role = messages.get(i).get("role");
            if ("user".equals(role)) return String.valueOf(messages.get(i).get("content"));
        }
        return "";
    }

    private String compact(String value) {
        if (value == null) return "";
        String result = value.replaceAll("[\\r\\n\\t]+", " ");
        return result.length() > 800 ? result.substring(0, 800) + "..." : result;
    }

    private String requestSummary(String action, List<Map<String, Object>> messages) {
        String query = lastUserMessage(messages);
        if ("AGENT_ANSWER_POLISH".equals(action)) return "[facts omitted, chars=" + query.length() + "]";
        return compact(query);
    }
}
