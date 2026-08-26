package com.hmdp.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.AgentSessionContext;
import com.hmdp.ai.dto.ChatStreamEventData;
import com.hmdp.ai.tool.AgentToolResult;
import com.hmdp.ai.tool.AgentToolRegistry;
import com.hmdp.ai.tool.BaseAgentTool;
import com.hmdp.ai.tool.ToolExecutionMode;
import com.hmdp.ai.tool.ToolExecutionRequest;
import com.hmdp.ai.tool.ToolExecutionResult;
import com.hmdp.ai.tool.ToolResultCompressor;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Executes independent read-only tools concurrently while serializing stateful tools. */
@Service
public class ToolExecutionOrchestrator {
    @Resource private AgentToolRegistry toolRegistry;
    @Resource private ObjectMapper objectMapper;
    @Resource private ToolResultCompressor resultCompressor;
    @Resource private AiProperties aiProperties;

    public List<ToolExecutionResult> execute(List<ToolExecutionRequest> requests, AgentSessionContext context) {
        return execute(requests, context, null);
    }

    public List<ToolExecutionResult> execute(List<ToolExecutionRequest> requests, AgentSessionContext context,
                                             Consumer<ChatStreamEventData> eventConsumer) {
        List<ToolExecutionResult> results = new ArrayList<ToolExecutionResult>();
        List<ToolExecutionRequest> parallel = new ArrayList<ToolExecutionRequest>();
        List<ToolExecutionRequest> sequential = new ArrayList<ToolExecutionRequest>();
        for (ToolExecutionRequest request : requests) {
            try {
                if (toolRegistry.find(request.getToolName()).executionMode() == ToolExecutionMode.PARALLEL_SAFE) parallel.add(request);
                else sequential.add(request);
            } catch (RuntimeException e) {
                results.add(failed(request, "不支持的工具"));
            }
        }

        if (!parallel.isEmpty()) {
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                List<CompletableFuture<ToolExecutionResult>> futures = new ArrayList<CompletableFuture<ToolExecutionResult>>();
                for (ToolExecutionRequest request : parallel) {
                    futures.add(CompletableFuture.supplyAsync(() -> executeOne(request, snapshot(context), eventConsumer), executor)
                            .completeOnTimeout(failed(request, "工具执行超时"), timeoutMs(), TimeUnit.MILLISECONDS));
                }
                for (CompletableFuture<ToolExecutionResult> future : futures) results.add(future.join());
            } finally {
                // A timed-out external call must not keep this request waiting for executor close.
                executor.shutdownNow();
            }
        }
        for (ToolExecutionRequest request : sequential) results.add(executeOne(request, context, eventConsumer));
        results.sort(Comparator.comparing(ToolExecutionResult::getOrder));
        return results;
    }

    public ToolExecutionResult executeOne(ToolExecutionRequest request, AgentSessionContext context) {
        return executeOne(request, context, null);
    }

    public ToolExecutionResult executeOne(ToolExecutionRequest request, AgentSessionContext context,
                                          Consumer<ChatStreamEventData> eventConsumer) {
        long startedAt = System.currentTimeMillis();
        ToolExecutionResult outcome = new ToolExecutionResult();
        outcome.setOrder(request.getOrder());
        outcome.setToolName(request.getToolName());
        try {
            BaseAgentTool tool = toolRegistry.find(request.getToolName());
            Map<String, Object> input = objectMapper.readValue(blankToObject(request.getArguments()), new TypeReference<Map<String, Object>>() { });
            if (request.getExplicitlyReferencedShopId() != null && isSingleShopTool(request.getToolName())
                    && !hasValidShopId(input.get("shopId"))) {
                input.put("shopId", request.getExplicitlyReferencedShopId());
            }
            enrichWithDeterministicContext(input, request.getToolName(), context);
            outcome.setEffectiveArguments(objectMapper.writeValueAsString(input));
            publishToolEvent(eventConsumer, "start", outcome);
            outcome.setResult(resultCompressor.compress(tool.execute(input)));
        } catch (Exception e) {
            outcome.setErrorMessage(e.getMessage() == null ? "工具执行失败" : e.getMessage());
        }
        outcome.setDurationMs(System.currentTimeMillis() - startedAt);
        publishToolEvent(eventConsumer, "end", outcome);
        return outcome;
    }

    private void publishToolEvent(Consumer<ChatStreamEventData> eventConsumer, String stage,
                                  ToolExecutionResult outcome) {
        if (eventConsumer == null) return;
        ChatStreamEventData event = new ChatStreamEventData();
        event.setEventName("tool_event");
        event.setStage(stage);
        event.setToolName(outcome.getToolName());
        event.setArguments(outcome.getEffectiveArguments());
        event.setDurationMs(outcome.getDurationMs());
        if (outcome.getResult() != null) {
            AgentToolResult result = outcome.getResult();
            Map<String, Object> output = new LinkedHashMap<String, Object>();
            output.put("summary", result.getSummary());
            output.put("facts", result.getFacts());
            event.setOutput(output);
        } else if (outcome.getErrorMessage() != null) {
            event.setOutput(outcome.getErrorMessage());
        }
        try {
            eventConsumer.accept(event);
        } catch (RuntimeException ignored) {
            // Streaming telemetry must not affect tool execution or tool result persistence.
        }
    }

    private AgentSessionContext snapshot(AgentSessionContext context) {
        return objectMapper.convertValue(context, AgentSessionContext.class);
    }

    private ToolExecutionResult failed(ToolExecutionRequest request, String message) {
        ToolExecutionResult result = new ToolExecutionResult();
        result.setOrder(request.getOrder());
        result.setToolName(request.getToolName());
        result.setEffectiveArguments(blankToObject(request.getArguments()));
        result.setErrorMessage(message);
        result.setDurationMs(0L);
        return result;
    }

    private int timeoutMs() {
        return aiProperties.getToolExecutionTimeoutMs() == null ? 2500 : aiProperties.getToolExecutionTimeoutMs();
    }

    private boolean isSingleShopTool(String toolName) {
        return "get_shop_detail".equals(toolName) || "query_shop_vouchers".equals(toolName)
                || "search_shop_evidence".equals(toolName);
    }

    private boolean hasValidShopId(Object value) {
        if (value == null) return false;
        if (value instanceof Number) return ((Number) value).longValue() > 0L;
        try { return Long.parseLong(String.valueOf(value).trim()) > 0L; }
        catch (NumberFormatException e) { return false; }
    }

    /** Materializes all state required by tools into a per-request input snapshot. */
    private void enrichWithDeterministicContext(Map<String, Object> input, String toolName, AgentSessionContext context) {
        if (context == null) return;
        if (isSingleShopTool(toolName) && !input.containsKey("shopId") && context.getFocusedShopId() != null) {
            input.put("shopId", context.getFocusedShopId());
        }
        if ("compare_shops".equals(toolName) && !input.containsKey("shopId") && context.getFocusedShopId() != null) {
            input.put("shopId", context.getFocusedShopId());
        }
        if ("search_alternative_shops".equals(toolName)) {
            input.put("shownShopIds", new ArrayList<Long>(context.getShownShopIds()));
            input.put("decisionRequest", context.getDecisionRequest());
            input.put("decisionConstraints", context.getDecisionConstraints());
        }
    }

    private String blankToObject(String arguments) {
        return arguments == null || arguments.trim().isEmpty() ? "{}" : arguments;
    }
}
