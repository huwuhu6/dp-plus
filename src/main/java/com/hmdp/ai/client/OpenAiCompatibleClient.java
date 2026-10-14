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
import org.springframework.web.client.ResourceAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

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
    private final ThreadLocal<List<Map<String,Object>>> transportAttempts = ThreadLocal.withInitial(ArrayList::new);

    /** 最近一次逻辑调用的传输证据；不包含认证头，也不作为语义输入。 */
    public List<Map<String,Object>> lastTransportAttempts() { return transportAttempts.get().stream().map(Map::copyOf).toList(); }

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
        transportAttempts.get().clear();
        var retry=aiProperties.getModelRetry();
        int maxAttempts=retry.getMaxAttempts();
        if(maxAttempts<1 || maxAttempts>5 || retry.getInitialDelayMs()<0 || retry.getMaxDelayMs()<retry.getInitialDelayMs()
                || retry.getMaxDelayMs()>60000 || retry.getMaxElapsedMs()<1 || retry.getMaxElapsedMs()>600000)
            throw new IllegalArgumentException("invalid model retry configuration");
        String callId=UUID.randomUUID().toString();
        long logicalStarted=nanoTime();
        HttpEntity<Map<String,Object>> request=new HttpEntity<>(body,headers);
        log.info("[AI][model] action={} model={} tools={} timeoutMs={} query={} event=REQUEST", action,
                aiProperties.getModel(), tools == null ? 0 : tools.size(), resolvedTimeout(timeoutMs),
                requestSummary(action, messages));
        RuntimeException lastFailure=null;
        for(int attempt=1;;attempt++) {
        long startedAt = System.currentTimeMillis();
        long remaining=retry.getMaxElapsedMs()-(nanoTime()-logicalStarted)/1_000_000;
        if(attempt>1 && remaining<=0)throw lastFailure;
        int attemptTimeout=(int)Math.max(1,Math.min(resolvedTimeout(timeoutMs),remaining-Math.min(5000,remaining/2)));
        Map<String,Object> trace=new LinkedHashMap<>();trace.put("logicalCallId",callId);trace.put("attempt",attempt);
        trace.put("readTimeoutMs",attemptTimeout);
        transportAttempts.get().add(trace);
        try {
            ResponseEntity<JsonNode> response = restTemplate(attemptTimeout).postForEntity(url, request, JsonNode.class);
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
            trace.put("success",true);trace.put("durationMs",System.currentTimeMillis()-startedAt);
            return response.getBody();
        } catch (HttpStatusCodeException e) {
            modelCallTracker.recordFailure();
            modelCallObservationService.record(observationPurpose(action), false, System.currentTimeMillis() - startedAt, null, null);
            log.warn("[AI][model] action={} model={} event=FAILURE durationMs={} status={} detail={}", action,
                    aiProperties.getModel(), System.currentTimeMillis() - startedAt, e.getRawStatusCode(),
                    compactError(e.getResponseBodyAsString()));
            trace.put("httpStatus",e.getStatusCode().value());
            trace.put("durationMs",System.currentTimeMillis()-startedAt);
            lastFailure=e;
            if(retryTransport(e,trace,attempt,maxAttempts,logicalStarted,retry))continue;
            throw e;
        } catch (RuntimeException e) {
            modelCallTracker.recordFailure();
            modelCallObservationService.record(observationPurpose(action), false, System.currentTimeMillis() - startedAt, null, null);
            log.warn("[AI][model] action={} model={} event=FAILURE durationMs={} errorType={} detail={}", action,
                    aiProperties.getModel(), System.currentTimeMillis() - startedAt, e.getClass().getSimpleName(),
                    compactError(e.getMessage()));
            trace.put("durationMs",System.currentTimeMillis()-startedAt);
            lastFailure=e;
            if(retryTransport(e,trace,attempt,maxAttempts,logicalStarted,retry))continue;
            throw e;
        }
        }
    }

    private boolean retryTransport(RuntimeException failure,Map<String,Object> trace,int attempt,int maxAttempts,
                                   long started,AiProperties.ModelRetryProperties retry) {
        trace.put("success",false);trace.put("failureType",failure.getClass().getName());
        String reason=retryReason(failure);trace.put("retryReason",reason);
        if(reason.equals("NON_RETRYABLE") || attempt>=maxAttempts || Thread.currentThread().isInterrupted())return false;
        long cap=retry.getInitialDelayMs();for(int n=1;n<attempt;n++)cap=Math.min(retry.getMaxDelayMs(),cap>retry.getMaxDelayMs()/2?retry.getMaxDelayMs():cap*2);
        cap=Math.min(cap,retry.getMaxDelayMs());
        long delay=Math.max(jitter(cap),retryAfter(failure));
        long remaining=retry.getMaxElapsedMs()-(nanoTime()-started)/1_000_000;
        // 只重试未得到有效响应的同一请求，不重放业务轮，不重跑工具，不覆盖首次失败。
        if(delay>=remaining) {trace.put("retryStopped","TIME_BUDGET");return false;}
        trace.put("retryDelayMs",delay);
        log.warn("[AI][model] event=RETRY callId={} attempt={} delayMs={} reason={}",trace.get("logicalCallId"),attempt,delay,reason);
        try {pause(delay);return true;}
        catch(InterruptedException interrupted) {Thread.currentThread().interrupt();trace.put("retryStopped","INTERRUPTED");return false;}
    }
    private String retryReason(RuntimeException failure) {
        if(failure instanceof HttpStatusCodeException http) {
            int status=http.getStatusCode().value();
            if(List.of(408,429,500,502,503,504).contains(status))return "HTTP_TRANSIENT";
            if(status==400) try {
                JsonNode error=objectMapper.readTree(http.getResponseBodyAsString()).path("error");
                if(error.path("code").asText().equals("invalid_parameter_error")
                        && error.path("message").asText().contains("sandbox grammar compile timed out"))return "GRAMMAR_COMPILE_TIMEOUT";
            } catch(Exception ignored) { /* 普通参数错误、非 JSON 错误不进入供应商临时失败白名单。 */ }
        }
        if(failure instanceof ResourceAccessException) {
            for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof javax.net.ssl.SSLException)return "NON_RETRYABLE";
            if(failure.getCause() instanceof java.io.IOException)return "NETWORK_IO";
        }
        return "NON_RETRYABLE";
    }
    private long retryAfter(RuntimeException failure) {
        if(!(failure instanceof HttpStatusCodeException http) || http.getResponseHeaders()==null)return 0;
        String value=http.getResponseHeaders().getFirst("Retry-After");if(value==null)return 0;
        try {return Math.multiplyExact(Math.max(0,Long.parseLong(value.trim())),1000);}
        catch(RuntimeException notSeconds) {
            try {return Math.max(0,ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-System.currentTimeMillis());}
            catch(RuntimeException invalidDate) {return 0;}
        }
    }
    protected long nanoTime(){return System.nanoTime();}
    protected long jitter(long cap){return cap==0?0:ThreadLocalRandom.current().nextLong(cap+1);}
    protected void pause(long ms)throws InterruptedException{Thread.sleep(ms);}

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
        return "OTHER";
    }

    protected RestTemplate restTemplate(Integer requestedTimeoutMs) {
        int timeoutMs = resolvedTimeout(requestedTimeoutMs);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.min(5000,timeoutMs));
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
