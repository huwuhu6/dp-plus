package com.hmdp.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.AgentSessionContext;
import com.hmdp.ai.dto.ChatStreamEventData;
import com.hmdp.ai.runtime.ConversationEventStatus;
import com.hmdp.ai.runtime.ConversationEventType;
import com.hmdp.ai.runtime.RuntimeTrace;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Executes independent read-only tools concurrently while serializing stateful tools. */
@Service
public class ToolExecutionOrchestrator {
    private static final String TOOL_TIMEOUT_MESSAGE = "工具执行超时";
    @Resource private AgentToolRegistry toolRegistry;
    @Resource private ObjectMapper objectMapper;
    @Resource private ToolResultCompressor resultCompressor;
    @Resource private AiProperties aiProperties;
    @Resource private ConversationEventService conversationEventService;

    public List<ToolExecutionResult> execute(List<ToolExecutionRequest> requests, AgentSessionContext context) {
        return execute(requests, context, null);
    }

    public List<ToolExecutionResult> execute(List<ToolExecutionRequest> requests, AgentSessionContext context,
                                             Consumer<ChatStreamEventData> eventConsumer) {
        return execute(requests, context, eventConsumer, null, null);
    }

    public List<ToolExecutionResult> execute(List<ToolExecutionRequest> requests, AgentSessionContext context,
                                             Consumer<ChatStreamEventData> eventConsumer, Long decisionSessionId,
                                             RuntimeTrace trace) {
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
                List<AtomicReference<ToolExecutionResult>> startedExecutions = new ArrayList<AtomicReference<ToolExecutionResult>>();
                for (ToolExecutionRequest request : parallel) {
                    AtomicReference<ToolExecutionResult> started = new AtomicReference<ToolExecutionResult>();
                    startedExecutions.add(started);
                    futures.add(CompletableFuture.supplyAsync(() -> executeOne(request, snapshot(context), eventConsumer,
                                    decisionSessionId, trace, started::set), executor)
                            .completeOnTimeout(failed(request, TOOL_TIMEOUT_MESSAGE), timeoutMs(), TimeUnit.MILLISECONDS));
                }
                for (int index = 0; index < futures.size(); index++) {
                    ToolExecutionResult result = futures.get(index).join();
                    if (TOOL_TIMEOUT_MESSAGE.equals(result.getErrorMessage()) && startedExecutions.get(index).get() != null) {
                        ToolExecutionResult started = startedExecutions.get(index).get();
                        result.setToolCallEventId(started.getToolCallEventId());
                        result.setEffectiveArguments(started.getEffectiveArguments());
                    }
                    results.add(result);
                }
            } finally {
                // A timed-out external call must not keep this request waiting for executor close.
                executor.shutdownNow();
            }
        }
        for (ToolExecutionRequest request : sequential) {
            results.add(executeOne(request, context, eventConsumer, decisionSessionId, trace, null));
        }
        results.sort(Comparator.comparing(ToolExecutionResult::getOrder));
        return results;
    }

    public ToolExecutionResult executeOne(ToolExecutionRequest request, AgentSessionContext context) {
        return executeOne(request, context, null);
    }

    public ToolExecutionResult executeOne(ToolExecutionRequest request, AgentSessionContext context,
                                          Consumer<ChatStreamEventData> eventConsumer) {
        return executeOne(request, context, eventConsumer, null, null, null);
    }

    private ToolExecutionResult executeOne(ToolExecutionRequest request, AgentSessionContext context,
                                           Consumer<ChatStreamEventData> eventConsumer, Long decisionSessionId,
                                           RuntimeTrace trace, Consumer<ToolExecutionResult> startedConsumer) {
        long startedAt = System.currentTimeMillis();
        String toolCallId = UUID.randomUUID().toString();
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
            if (conversationEventService != null && trace != null) {
                Map<String, Object> call = new LinkedHashMap<String, Object>();
                call.put("tool", request.getToolName());
                call.put("arguments", outcome.getEffectiveArguments());
                call.put("decisionSessionId", decisionSessionId);
                call.put("turnNo", context == null ? null : context.getTurnNo());
                outcome.setToolCallEventId(conversationEventService.persistDurableEvent(trace,
                        ConversationEventType.TOOL_CALL, ConversationEventStatus.RUNNING, null, null, call, null));
            }
            if (startedConsumer != null) startedConsumer.accept(outcome);
            publishToolEvent(eventConsumer, "start", toolCallId, outcome);
            outcome.setResult(resultCompressor.compress(tool.execute(input)));
        } catch (Exception e) {
            outcome.setErrorMessage(e.getMessage() == null ? "工具执行失败" : e.getMessage());
        }
        outcome.setDurationMs(System.currentTimeMillis() - startedAt);
        publishToolEvent(eventConsumer, "end", toolCallId, outcome);
        return outcome;
    }

    private void publishToolEvent(Consumer<ChatStreamEventData> eventConsumer, String stage, String toolCallId,
                                  ToolExecutionResult outcome) {
        if (eventConsumer == null) return;
        ChatStreamEventData event = new ChatStreamEventData();
        event.setEventName("tool_event");
        event.setStage(stage);
        event.setToolName(outcome.getToolName());
        event.setToolCallId(toolCallId);
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
            input.put("shownShopIds", new ArrayList<Long>(context.getShownShopIdsSnapshot()));
            input.put("decisionRequest", context.getDecisionRequest());
            input.put("decisionConstraints", context.getDecisionConstraints());
        }
    }

    private String blankToObject(String arguments) {
        return arguments == null || arguments.trim().isEmpty() ? "{}" : arguments;
    }
}
