package com.hmdp.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.service.AiModelCallTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Isolates a fast, OpenAI-compatible model for context rewriting from the main agent model. */
@Component
public class QueryRewriteClient {
    private static final Logger log = LoggerFactory.getLogger(QueryRewriteClient.class);

    @Resource private AiProperties aiProperties;
    @Resource private AiModelCallTracker modelCallTracker;

    public boolean isConfigured() {
        return aiProperties.getQueryRewrite() != null && aiProperties.getQueryRewrite().isConfigured();
    }

    public String rewrite(List<Map<String, Object>> messages) {
        AiProperties.QueryRewriteProperties properties = aiProperties.getQueryRewrite();
        if (!isConfigured()) throw new IllegalStateException("Query rewrite model is not configured");
        long startedAt = System.currentTimeMillis();
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", properties.getModel());
        body.put("messages", messages);
        body.put("temperature", 0D);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.getApiKey());
        log.info("[AI][query-rewrite] provider={} model={} messages={} timeoutMs={} event=REQUEST",
                properties.getProvider(), properties.getModel(), messages == null ? 0 : messages.size(), timeout(properties));
        try {
            ResponseEntity<JsonNode> response = restTemplate(timeout(properties)).postForEntity(
                    trimTrailingSlash(properties.getBaseUrl()) + "/chat/completions", new HttpEntity<Object>(body, headers), JsonNode.class);
            JsonNode payload = response.getBody();
            String content = payload == null ? "" : payload.path("choices").path(0).path("message").path("content").asText();
            if (!response.getStatusCode().is2xxSuccessful() || content.trim().isEmpty()) throw new IllegalStateException("Query rewrite model returned no content");
            modelCallTracker.recordSuccess(payload.path("usage").path("prompt_tokens").isNumber() ? payload.path("usage").path("prompt_tokens").asInt() : null,
                    payload.path("usage").path("completion_tokens").isNumber() ? payload.path("usage").path("completion_tokens").asInt() : null);
            log.info("[AI][query-rewrite] provider={} model={} event=SUCCESS durationMs={} chars={}",
                    properties.getProvider(), properties.getModel(), System.currentTimeMillis() - startedAt, content.length());
            return content;
        } catch (HttpStatusCodeException e) {
            modelCallTracker.recordFailure();
            log.warn("[AI][query-rewrite] provider={} model={} event=FAILURE durationMs={} status={}",
                    properties.getProvider(), properties.getModel(), System.currentTimeMillis() - startedAt, e.getRawStatusCode());
            throw e;
        } catch (RuntimeException e) {
            modelCallTracker.recordFailure();
            log.warn("[AI][query-rewrite] provider={} model={} event=FAILURE durationMs={} errorType={}",
                    properties.getProvider(), properties.getModel(), System.currentTimeMillis() - startedAt, e.getClass().getSimpleName());
            throw e;
        }
    }

    private RestTemplate restTemplate(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs); factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
    }
    private int timeout(AiProperties.QueryRewriteProperties properties) { return properties.getTimeoutMs() == null ? 8000 : properties.getTimeoutMs(); }
    private String trimTrailingSlash(String value) { return value == null ? "" : value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
}
