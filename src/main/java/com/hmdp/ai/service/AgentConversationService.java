package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.client.SpringAiTextClient;
import com.hmdp.ai.client.SpringAiToolPlanner;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.AgentConversationRequest;
import com.hmdp.ai.dto.AgentConversationResponse;
import com.hmdp.ai.dto.AgentSessionContext;
import com.hmdp.ai.dto.AgentToolTraceItem;
import com.hmdp.ai.dto.ChatStreamEventData;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.entity.AiAgentToolCall;
import com.hmdp.ai.entity.AiConversationEvent;
import com.hmdp.ai.entity.AiDecisionMessage;
import com.hmdp.ai.entity.AiDecisionSession;
import com.hmdp.ai.mapper.AiAgentToolCallMapper;
import com.hmdp.ai.mapper.AiConversationEventMapper;
import com.hmdp.ai.runtime.ConversationEventStatus;
import com.hmdp.ai.runtime.ConversationEventType;
import com.hmdp.ai.mapper.AiDecisionMessageMapper;
import com.hmdp.ai.mapper.AiDecisionSessionMapper;
import com.hmdp.ai.tool.AgentToolRegistry;
import com.hmdp.ai.tool.AgentToolResult;
import com.hmdp.ai.tool.BaseAgentTool;
import com.hmdp.ai.tool.ToolExecutionRequest;
import com.hmdp.ai.tool.ToolExecutionResult;
import com.hmdp.ai.tool.ToolResultCompressor;
import com.hmdp.utils.UserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class AgentConversationService {
    private static final Logger log = LoggerFactory.getLogger(AgentConversationService.class);
    @Resource private AiDecisionSessionMapper sessionMapper;
    @Resource private AiDecisionMessageMapper messageMapper;
    @Resource private AiAgentToolCallMapper toolCallMapper;
    @Resource private AiConversationEventMapper conversationEventMapper;
    @Resource private ConversationEventService conversationEventService;
    @Resource private AgentToolRegistry toolRegistry;
    @Resource private OpenAiCompatibleClient aiClient;
    @Resource private SpringAiTextClient springAiTextClient;
    @Resource private SpringAiToolPlanner springAiToolPlanner;
    @Resource private ToolExecutionOrchestrator toolExecutionOrchestrator;
    @Resource private ToolResultCompressor toolResultCompressor;
    @Resource private AgentToolStateReducer agentToolStateReducer = new AgentToolStateReducer();
    @Resource private AiProperties aiProperties;
    @Resource private ObjectMapper objectMapper;

    public AgentConversationResponse converse(Long sessionId, AgentConversationRequest request, AgentSessionContext workingMemoryContext) {
        return converse(sessionId, request, workingMemoryContext, null);
    }

    public AgentConversationResponse converse(Long sessionId, AgentConversationRequest request,
                                              AgentSessionContext workingMemoryContext,
                                              Consumer<ChatStreamEventData> eventConsumer) {
        if (workingMemoryContext == null) throw new IllegalArgumentException("workingMemoryContext 不能为空");
        if (request == null || request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        AiDecisionSession session = sessionMapper.selectById(sessionId);
        if (session == null) throw new IllegalArgumentException("决策记录不存在");
        ensureOwner(session);
        if (!"COMPLETED".equals(session.getStatus())) {
            throw new IllegalArgumentException("仅已完成推荐的会话支持商户追问");
        }
        try {
            AgentSessionContext context = workingMemoryContext;
            context.setTurnNo(context.getTurnNo() + 1);
            String userMessage = request.getMessage().trim();
            List<BoundFactTask> compoundTasks = resolveCompoundFactTasks(userMessage, context);
            ReferenceResolution reference = resolveShopReference(userMessage, context);
            if (reference.isAmbiguous()) {
                log.info("[AI][agent] event=REFERENCE_AMBIGUOUS sessionId={} query={} candidates={}", sessionId,
                        compact(userMessage), reference.candidateNames);
                saveMessage(sessionId, "USER", "AGENT_FOLLOW_UP", userMessage);
                AgentConversationResponse response = ambiguousReferenceResponse(sessionId, context, reference.candidateNames);
                saveMessage(sessionId, "ASSISTANT", "AGENT_REFERENCE_CLARIFY", response.getAnswer());
                return response;
            }
            if (reference.shop != null) {
                context.setFocusedShopId(reference.shop.getShopId());
                context.setFocusedShopName(reference.shop.getShopName());
                log.info("[AI][agent] event=REFERENCE_RESOLVED sessionId={} query={} shopId={} shopName={}", sessionId,
                        compact(userMessage), reference.shop.getShopId(), reference.shop.getShopName());
            }
            log.info("[AI][agent] event=TURN_START sessionId={} turnNo={} query={} context={}", sessionId,
                    context.getTurnNo(), compact(userMessage), compact(contextSummary(context)));
            saveMessage(sessionId, "USER", "AGENT_FOLLOW_UP", userMessage);
            ToolPlanningResult planning = compoundTasks.isEmpty()
                    ? runToolLoop(sessionId, context, userMessage, reference.shop == null ? null : reference.shop.getShopId(),
                    eventConsumer)
                    : runBoundFactTasks(sessionId, context, compoundTasks, eventConsumer);
            List<AgentToolResult> results = planning.results;
            boolean usedModel = planning.usedModel;
            if (results.isEmpty()) {
                try {
                    results.addAll(runFallbackTools(sessionId, context.getTurnNo(), userMessage, context,
                            reference.shop == null ? null : reference.shop.getShopId(), eventConsumer));
                    if (results.isEmpty()) results.add(new AgentToolResult().summary("业务数据暂不可用")
                            .displayText("抱歉，这次查询没有完成。你可以稍后重试，或换一种问法。"));
                } catch (IllegalArgumentException e) {
                    results.add(new AgentToolResult().summary("业务数据暂不可用")
                            .displayText("抱歉，这次查询没有完成。你可以稍后重试，或换一种问法。"));
                }
                usedModel = false;
            }
            for (AgentToolResult result : results) agentToolStateReducer.apply(context, result);

            AgentConversationResponse response = response(sessionId, context, results, usedModel);
            response.setAnswer(polishAnswer(userMessage, response.getAnswer(), usedModel));
            saveMessage(sessionId, "ASSISTANT", "AGENT_TOOL_ANSWER", response.getAnswer());
            return response;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("商户追问无法处理", e);
        }
    }

    public List<AiAgentToolCall> getToolCalls(Long sessionId) {
        AiDecisionSession session = sessionMapper.selectById(sessionId);
        if (session == null) throw new IllegalArgumentException("决策记录不存在");
        ensureOwner(session);
        if (!hasText(session.getChatId()) || !hasText(session.getTraceId())) return new ArrayList<AiAgentToolCall>();
        List<AiAgentToolCall> calls = new ArrayList<AiAgentToolCall>();
        List<AiConversationEvent> events = conversationEventMapper.selectList(new QueryWrapper<AiConversationEvent>()
                .eq("chat_id", session.getChatId()).eq("trace_id", session.getTraceId())
                .eq("event_type", ConversationEventType.TOOL_CALL.name()).orderByAsc("sequence_no"));
        for (AiConversationEvent event : events) {
            try {
                Map<String, Object> value = objectMapper.readValue(event.getEventResult(), new TypeReference<Map<String, Object>>() { });
                if (!sessionId.equals(asLong(value.get("decisionSessionId")))) continue;
                AiAgentToolCall call = new AiAgentToolCall();
                call.setId(event.getId()); call.setSessionId(sessionId);
                call.setToolName(String.valueOf(value.get("tool"))); call.setToolInputJson(String.valueOf(value.get("arguments")));
                call.setTurnNo(asInteger(value.get("turnNo"))); call.setStatus(event.getStatus());
                calls.add(call);
            } catch (Exception ignored) { }
        }
        return calls;
    }

    /**
     * Pure candidate-reference guard for the chat gateway. Its context must be projected
     * from ConversationWorkingMemory; it deliberately does not read a decision-session cache.
     */
    public boolean hasCandidateReference(String message, AgentSessionContext context) {
        if (context == null || context.getCandidatePoolSnapshot() == null || context.getCandidatePoolSnapshot().isEmpty()) return false;
        ReferenceResolution reference = resolveShopReference(message == null ? "" : message.trim(), context);
        return reference.shop != null || reference.isAmbiguous()
                || !resolveCompoundFactTasks(message == null ? "" : message.trim(), context).isEmpty();
    }

    /**
     * A message such as "第一家有券，第二家评价如何" is not ambiguous: each fact has a
     * concrete candidate binding. Keep these bindings outside the model so multiple tool calls
     * cannot collapse onto the focused shop.
     */
    private ToolPlanningResult runBoundFactTasks(Long sessionId, AgentSessionContext context,
                                                 List<BoundFactTask> tasks,
                                                 Consumer<ChatStreamEventData> eventConsumer) {
        List<AgentToolResult> results = new ArrayList<AgentToolResult>();
        log.info("[AI][agent] event=COMPOUND_REFERENCE_RESOLVED sessionId={} turnNo={} tasks={}", sessionId,
                context.getTurnNo(), tasks.stream().map(BoundFactTask::summary).toList());
        for (BoundFactTask task : tasks) {
            try {
                results.add(executeTool(sessionId, context.getTurnNo(), task.toolName, task.arguments(), context,
                        task.shop.getShopId(), eventConsumer));
            } catch (IllegalArgumentException e) {
                log.warn("[AI][agent] event=COMPOUND_TOOL_FAILURE sessionId={} turnNo={} tool={} shopId={} errorType={}",
                        sessionId, context.getTurnNo(), task.toolName, task.shop.getShopId(), e.getClass().getSimpleName());
            }
        }
        return new ToolPlanningResult(results, false);
    }

    private ToolPlanningResult runToolLoop(Long sessionId, AgentSessionContext context, String userMessage,
                                           Long explicitlyReferencedShopId,
                                           Consumer<ChatStreamEventData> eventConsumer) {
        List<AgentToolResult> results = new ArrayList<AgentToolResult>();
        if (!aiProperties.isConfigured()) return new ToolPlanningResult(results, false);
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        messages.add(message("system", "你是点评消费决策 Agent 的工具规划器。必须只使用提供的只读工具取得商户事实；不得编造商户、券、评价或价格。用户同时询问评价和优惠等独立事实时可调用多个工具，最多 3 个；候选扩展工具会修改会话候选池，不能与其他工具组合。"));
        messages.add(message("system", "会话上下文：" + contextSummary(context)));
        messages.add(message("user", userMessage));
        try {
            List<SpringAiToolPlanner.ToolPlan> plans = springAiToolPlanner.planAll(messages,
                    toolRegistry.springAiCallbacks(), "AGENT_TOOL_PLANNING");
            if (plans.isEmpty()) {
                log.warn("[AI][agent] event=PLAN_EMPTY sessionId={} turnNo={} query={}", sessionId,
                        context.getTurnNo(), compact(userMessage));
                return new ToolPlanningResult(results, false);
            }
            List<ToolExecutionRequest> requests = new ArrayList<ToolExecutionRequest>();
            for (int index = 0; index < plans.size(); index++) {
                SpringAiToolPlanner.ToolPlan plan = plans.get(index);
                log.info("[AI][agent] event=TOOL_PLAN sessionId={} turnNo={} order={} tool={} arguments={}", sessionId,
                        context.getTurnNo(), index, plan.getName(), compact(plan.getArguments()));
                requests.add(new ToolExecutionRequest(index, plan.getName(), plan.getArguments(), explicitlyReferencedShopId));
            }
            addMissingCompoundFactTools(requests, userMessage, explicitlyReferencedShopId);
            log.info("[AI][agent] event=TOOL_EXECUTION_BATCH sessionId={} turnNo={} plannedTools={} parallelEligible={}", sessionId,
                    context.getTurnNo(), requests.stream().map(ToolExecutionRequest::getToolName).toList(),
                    requests.stream().filter(item -> !"search_alternative_shops".equals(item.getToolName())).count());
            for (ToolExecutionResult execution : toolExecutionOrchestrator.execute(requests, context, eventConsumer,
                    sessionId, conversationEventService == null ? null : conversationEventService.currentTrace())) {
                persistToolExecution(sessionId, context.getTurnNo(), execution);
                if (execution.isSuccess()) results.add(execution.getResult());
            }
            return new ToolPlanningResult(results, true);
        } catch (RuntimeException e) {
            log.warn("[AI][agent] event=PLAN_FALLBACK sessionId={} turnNo={} query={} errorType={} detail={}", sessionId,
                    context.getTurnNo(), compact(userMessage), e.getClass().getSimpleName(), compact(e.getMessage()));
            return new ToolPlanningResult(results, false);
        }
    }

    private AgentToolResult executeTool(Long sessionId, Integer turnNo, String toolName, String arguments,
                                        AgentSessionContext context, Long explicitlyReferencedShopId,
                                        Consumer<ChatStreamEventData> eventConsumer) {
        long startedAt = System.currentTimeMillis();
        String toolCallId = java.util.UUID.randomUUID().toString();
        AiAgentToolCall record = new AiAgentToolCall();
        record.setSessionId(sessionId); record.setTurnNo(turnNo); record.setToolName(toolName);
        Long callEventId = null;
        boolean toolStarted = false;
        try {
            Map<String, Object> input = objectMapper.readValue(arguments, new TypeReference<Map<String, Object>>() { });
            materializeToolInput(input, toolName, context, explicitlyReferencedShopId);
            String effectiveArguments = objectMapper.writeValueAsString(input);
            record.setToolInputJson(effectiveArguments);
            callEventId = persistToolCall(sessionId, turnNo, toolName, effectiveArguments, "DIRECT_RULE");
            publishToolEvent(eventConsumer, "start", toolCallId, toolName, effectiveArguments, null, null, null);
            log.info("[AI][agent] event=TOOL_START sessionId={} turnNo={} tool={} arguments={}", sessionId, turnNo,
                    toolName, compact(effectiveArguments));
            toolStarted = true;
            AgentToolResult result = toolResultCompressor.compress(toolRegistry.find(toolName).execute(input));
            result.setToolName(toolName);
            result.setDurationMs(System.currentTimeMillis() - startedAt);
            record.setStatus("SUCCESS");
            record.setToolOutputJson(objectMapper.writeValueAsString(result.getFacts()));
            record.setDurationMs(System.currentTimeMillis() - startedAt);
            try {
                toolCallMapper.insert(record);
            } catch (RuntimeException auditFailure) {
                if (conversationEventService != null) conversationEventService.markTraceIncomplete("TOOL_AUDIT_ROW_PERSIST_FAILURE");
            }
            persistToolResult(callEventId, sessionId, turnNo, toolName, result.getFacts(), null,
                    ConversationEventStatus.SUCCESS, result.getDurationMs(), "DIRECT_RULE");
            publishToolEvent(eventConsumer, "end", toolCallId, toolName, effectiveArguments, result.getDurationMs(), result, null);
            log.info("[AI][agent] event=TOOL_SUCCESS sessionId={} turnNo={} tool={} durationMs={} result={}", sessionId,
                    turnNo, toolName, result.getDurationMs(), compact(record.getToolOutputJson()));
            return result;
        } catch (Exception e) {
            record.setStatus("FAILED");
            record.setDurationMs(System.currentTimeMillis() - startedAt);
            publishToolEvent(eventConsumer, "end", toolCallId, toolName, record.getToolInputJson(), record.getDurationMs(), null,
                    e.getMessage());
            record.setToolOutputJson("{\"error\":\"工具执行失败\"}");
            try {
                toolCallMapper.insert(record);
            } catch (RuntimeException auditFailure) {
                if (conversationEventService != null) conversationEventService.markTraceIncomplete("TOOL_AUDIT_ROW_PERSIST_FAILURE");
            }
            if (toolStarted) {
                persistToolResult(callEventId, sessionId, turnNo, toolName, null, e.getMessage(),
                        ConversationEventStatus.FAILED, record.getDurationMs(), "DIRECT_RULE");
            }
            log.warn("[AI][agent] event=TOOL_FAILURE sessionId={} turnNo={} tool={} errorType={} detail={}", sessionId,
                    turnNo, toolName, e.getClass().getSimpleName(), compact(e.getMessage()));
            throw new IllegalArgumentException("工具 " + toolName + " 无法执行");
        }
    }

    private void publishToolEvent(Consumer<ChatStreamEventData> eventConsumer, String stage, String toolCallId, String toolName,
                                  String arguments, Long durationMs, AgentToolResult result, String errorMessage) {
        if (eventConsumer == null) return;
        ChatStreamEventData event = new ChatStreamEventData();
        event.setEventName("tool_event");
        event.setStage(stage);
        event.setToolName(toolName);
        event.setToolCallId(toolCallId);
        event.setArguments(arguments);
        event.setDurationMs(durationMs);
        if (result != null) {
            Map<String, Object> output = new LinkedHashMap<String, Object>();
            output.put("summary", result.getSummary());
            output.put("facts", result.getFacts());
            event.setOutput(output);
        } else if (errorMessage != null) {
            event.setOutput(errorMessage);
        }
        try {
            eventConsumer.accept(event);
        } catch (RuntimeException ignored) {
            // Streaming telemetry must not affect tool execution or tool result persistence.
        }
    }

    private void persistToolExecution(Long sessionId, Integer turnNo, ToolExecutionResult execution) {
        if (conversationEventService != null && execution.getToolCallEventId() == null) {
            throw new IllegalStateException("Tool execution was not durably accepted");
        }
        AiAgentToolCall record = new AiAgentToolCall();
        record.setSessionId(sessionId); record.setTurnNo(turnNo); record.setToolName(execution.getToolName());
        record.setToolInputJson(execution.getEffectiveArguments());
        record.setDurationMs(execution.getDurationMs());
        if (execution.isSuccess()) {
            try {
                record.setStatus("SUCCESS");
                record.setToolOutputJson(objectMapper.writeValueAsString(execution.getResult().getFacts()));
                toolCallMapper.insert(record);
                persistToolResult(execution.getToolCallEventId(), sessionId, turnNo, execution.getToolName(),
                        execution.getResult().getFacts(), null, ConversationEventStatus.SUCCESS,
                        execution.getDurationMs(), "PLANNED_TOOL");
                log.info("[AI][agent] event=TOOL_SUCCESS sessionId={} turnNo={} tool={} durationMs={} result={}", sessionId,
                        turnNo, execution.getToolName(), execution.getDurationMs(), compact(record.getToolOutputJson()));
            } catch (Exception e) {
                if (conversationEventService != null) conversationEventService.markTraceIncomplete("TOOL_EXECUTION_AUDIT_FAILURE");
                log.warn("[AI][agent] event=TOOL_AUDIT_FAILURE sessionId={} turnNo={} tool={} errorType={}", sessionId,
                        turnNo, execution.getToolName(), e.getClass().getSimpleName());
            }
        } else {
            record.setStatus("FAILED");
            record.setToolOutputJson("{\"error\":\"工具执行失败\"}");
            toolCallMapper.insert(record);
            ConversationEventStatus status = "工具执行超时".equals(execution.getErrorMessage())
                    ? ConversationEventStatus.TIMEOUT : ConversationEventStatus.FAILED;
            persistToolResult(execution.getToolCallEventId(), sessionId, turnNo, execution.getToolName(), null,
                    execution.getErrorMessage(), status, execution.getDurationMs(), "PLANNED_TOOL");
            log.warn("[AI][agent] event=TOOL_FAILURE sessionId={} turnNo={} tool={} durationMs={} detail={}", sessionId,
                    turnNo, execution.getToolName(), execution.getDurationMs(), compact(execution.getErrorMessage()));
        }
    }

    private List<AgentToolResult> runFallbackTools(Long sessionId, Integer turnNo, String message, AgentSessionContext context,
                                                   Long explicitlyReferencedShopId,
                                                   Consumer<ChatStreamEventData> eventConsumer) {
        List<String> toolNames = fallbackToolNames(message);
        List<AgentToolResult> results = new ArrayList<AgentToolResult>();
        for (String toolName : toolNames) {
            try {
                AgentToolResult result = executeTool(sessionId, turnNo, toolName, "{}", context,
                        explicitlyReferencedShopId, eventConsumer);
                results.add(result);
            } catch (IllegalArgumentException e) {
                log.warn("[AI][agent] event=FALLBACK_TOOL_FAILURE sessionId={} turnNo={} tool={} errorType={}", sessionId,
                        turnNo, toolName, e.getClass().getSimpleName());
            }
        }
        return results;
    }

    private List<String> fallbackToolNames(String message) {
        boolean voucher = message.contains("优惠") || message.contains("券");
        boolean evidence = message.contains("评价") || message.contains("评论") || message.contains("笔记")
                || message.contains("排队") || message.contains("环境");
        List<String> result = new ArrayList<String>();
        if (voucher) result.add("query_shop_vouchers");
        if (evidence) result.add("search_shop_evidence");
        if (!result.isEmpty()) return result;
        if (message.contains("还有") || message.contains("其他") || message.contains("换一家")) result.add("search_alternative_shops");
        else result.add("get_shop_detail");
        return result;
    }

    private Long persistToolCall(Long sessionId, Integer turnNo, String toolName, String arguments, String executionMode) {
        if (conversationEventService == null) return null;
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("tool", toolName); payload.put("arguments", arguments);
        payload.put("decisionSessionId", sessionId); payload.put("turnNo", turnNo);
        payload.put("executionMode", executionMode);
        return conversationEventService.persistDurableEvent(ConversationEventType.TOOL_CALL,
                ConversationEventStatus.RUNNING, null, null, payload, null);
    }

    private void persistToolResult(Long callEventId, Long sessionId, Integer turnNo, String toolName, Object facts,
                                   String error, ConversationEventStatus status, Long durationMs, String executionMode) {
        if (conversationEventService == null) return;
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("tool", toolName); payload.put("decisionSessionId", sessionId); payload.put("turnNo", turnNo);
        if (facts != null) payload.put("facts", facts);
        if (error != null) payload.put("error", error);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("durationMs", durationMs); metadata.put("executionMode", executionMode);
        try {
            conversationEventService.persistDurableEvent(ConversationEventType.TOOL_RESULT, status,
                    null, callEventId, payload, metadata);
        } catch (RuntimeException persistenceFailure) {
            conversationEventService.markTraceIncomplete("TOOL_RESULT_PERSIST_FAILURE");
            log.warn("[AI][agent] event=TOOL_RESULT_PERSIST_FAILURE sessionId={} tool={} errorType={}",
                    sessionId, toolName, persistenceFailure.getClass().getSimpleName());
        }
    }

    private void addMissingCompoundFactTools(List<ToolExecutionRequest> requests, String message, Long explicitlyReferencedShopId) {
        List<String> expected = fallbackToolNames(message);
        if (expected.size() != 2 || !expected.contains("query_shop_vouchers") || !expected.contains("search_shop_evidence")) return;
        int nextOrder = requests.stream().map(ToolExecutionRequest::getOrder).max(Integer::compareTo).orElse(-1) + 1;
        for (String toolName : expected) {
            boolean exists = requests.stream().anyMatch(item -> toolName.equals(item.getToolName()));
            if (!exists) requests.add(new ToolExecutionRequest(nextOrder++, toolName, "{}", explicitlyReferencedShopId));
        }
    }

    private AgentConversationResponse response(Long sessionId, AgentSessionContext context, List<AgentToolResult> results,
                                               boolean usedModel) {
        AgentConversationResponse response = new AgentConversationResponse();
        response.setSessionId(sessionId); response.setTurnNo(context.getTurnNo());
        response.setFocusedShopId(context.getFocusedShopId()); response.setFocusedShopName(context.getFocusedShopName());
        response.setUsedModel(usedModel);
        if (!usedModel) response.setDegradedReason("模型工具规划未启用或调用失败，本次按本地意图路由查询业务数据。");
        StringBuilder answer = new StringBuilder();
        for (AgentToolResult result : results) {
            if (answer.length() > 0) answer.append("\n\n");
            answer.append(result.getDisplayText());
            agentToolStateReducer.markShown(context, result);
            response.getToolTrace().add(new AgentToolTraceItem(result.getToolName(), result.getSummary(), result.getDurationMs()));
        }
        response.setAnswer(answer.toString());
        return response;
    }

    private String polishAnswer(String userMessage, String factualAnswer, boolean planningUsedModel) {
        if (!planningUsedModel || !Boolean.TRUE.equals(aiProperties.getNarrativeEnabled())) return factualAnswer;
        try {
            String answer = springAiTextClient.chatText(java.util.Arrays.asList(
                    message("system", "你是点评消费决策助手。基于已检索到的事实，用简洁自然的中文回答用户。不得补充、猜测或改写任何事实；证据不足时直接说明。"),
                    message("user", "用户问题：" + userMessage + "\n已检索事实：\n" + factualAnswer)),
                    "AGENT_ANSWER_POLISH", aiProperties.getAnswerPolishTimeoutMs()).trim();
            if (!answer.isEmpty()) {
                log.info("[AI][agent] event=ANSWER_POLISH_SUCCESS query={} answer={}", compact(userMessage), compact(answer));
                return answer;
            }
        } catch (RuntimeException e) {
            log.warn("[AI][agent] event=ANSWER_POLISH_FALLBACK query={} errorType={} detail={}", compact(userMessage),
                    e.getClass().getSimpleName(), compact(e.getMessage()));
        }
        return factualAnswer;
    }

    private String contextSummary(AgentSessionContext context) {
        return "当前聚焦商户=" + (context.getFocusedShopId() == null ? "无" : context.getFocusedShopId() + ":" + context.getFocusedShopName())
                + "；已展示商户=" + shownShopSummary(context);
    }

    private String shownShopSummary(AgentSessionContext context) {
        StringBuilder value = new StringBuilder("[");
        for (DecisionRecommendation item : context.getCandidatePoolSnapshot()) {
            if (value.length() > 1) value.append("; ");
            value.append(item.getShopId()).append(":").append(item.getShopName());
        }
        return value.append("]").toString();
    }

    private ReferenceResolution resolveShopReference(String message, AgentSessionContext context) {
        // Explicitly named/numbered multi-shop facts are actionable tasks, not an ambiguous reference.
        if (!resolveCompoundFactTasks(message, context).isEmpty()) {
            return new ReferenceResolution(null, new ArrayList<String>());
        }
        DecisionRecommendation ordinalShop = resolveOrdinalReference(message, context);
        if (ordinalShop != null) return new ReferenceResolution(ordinalShop, new ArrayList<String>());

        List<DecisionRecommendation> exactMatches = new ArrayList<DecisionRecommendation>();
        for (DecisionRecommendation item : context.getCandidatePoolSnapshot()) {
            if (containsNormalizedShopName(message, item.getShopName())) exactMatches.add(item);
        }
        if (exactMatches.size() == 1) return new ReferenceResolution(exactMatches.get(0), new ArrayList<String>());
        if (exactMatches.size() > 1) return new ReferenceResolution(null, shopNames(exactMatches));

        List<DecisionRecommendation> matches = new ArrayList<DecisionRecommendation>();
        for (DecisionRecommendation item : context.getCandidatePoolSnapshot()) {
            if (matchesShop(message, item.getShopName())) matches.add(item);
        }
        if (matches.size() == 1) return new ReferenceResolution(matches.get(0), new ArrayList<String>());
        List<String> names = shopNames(matches);
        if (!names.isEmpty()) return new ReferenceResolution(null, names);

        DecisionRecommendation focusedShop = focusedShop(context);
        if (focusedShop != null && (hasFocusedShopPronoun(message, context) || isImplicitFocusedFactQuery(message))) {
            return new ReferenceResolution(focusedShop, new ArrayList<String>());
        }
        return new ReferenceResolution(null, names);
    }

    private List<BoundFactTask> resolveCompoundFactTasks(String message, AgentSessionContext context) {
        List<ShopMention> mentions = explicitShopMentions(message, context);
        if (mentions.size() < 2) return new ArrayList<BoundFactTask>();
        List<BoundFactTask> tasks = new ArrayList<BoundFactTask>();
        for (int index = 0; index < mentions.size(); index++) {
            ShopMention mention = mentions.get(index);
            int end = index + 1 < mentions.size() ? mentions.get(index + 1).position : message.length();
            String clause = message.substring(mention.position, end);
            if (containsVoucherSignal(clause)) tasks.add(new BoundFactTask("query_shop_vouchers", mention.shop));
            if (containsEvidenceSignal(clause)) tasks.add(new BoundFactTask("search_shop_evidence", mention.shop));
        }
        return tasks;
    }

    private List<ShopMention> explicitShopMentions(String message, AgentSessionContext context) {
        List<ShopMention> mentions = new ArrayList<ShopMention>();
        if (message == null || context == null || context.getCandidatePoolSnapshot() == null) return mentions;
        addOrdinalMention(mentions, message, "\u7b2c\u4e00\u5bb6", 0, context);
        addOrdinalMention(mentions, message, "\u9996\u9009", 0, context);
        addOrdinalMention(mentions, message, "\u7b2c\u4e8c\u5bb6", 1, context);
        addOrdinalMention(mentions, message, "\u7b2c\u4e09\u5bb6", 2, context);
        for (DecisionRecommendation shop : context.getCandidatePoolSnapshot()) {
            if (shop.getShopName() == null || shop.getShopName().trim().isEmpty()) continue;
            int position = message.indexOf(shop.getShopName());
            if (position >= 0) mentions.add(new ShopMention(position, shop));
        }
        mentions.sort(java.util.Comparator.comparingInt(item -> item.position));
        List<ShopMention> unique = new ArrayList<ShopMention>();
        for (ShopMention mention : mentions) {
            boolean duplicate = unique.stream().anyMatch(item -> item.position == mention.position
                    || (item.shop.getShopId() != null && item.shop.getShopId().equals(mention.shop.getShopId())));
            if (!duplicate) unique.add(mention);
        }
        return unique;
    }

    private void addOrdinalMention(List<ShopMention> mentions, String message, String token, int candidateIndex,
                                   AgentSessionContext context) {
        if (candidateIndex >= context.getCandidatePoolSnapshot().size()) return;
        int position = message.indexOf(token);
        if (position >= 0) mentions.add(new ShopMention(position, context.getCandidatePoolSnapshot().get(candidateIndex)));
    }

    private boolean containsVoucherSignal(String text) {
        return text.contains("\u4f18\u60e0") || text.contains("\u4ee3\u91d1\u5238") || text.contains("\u56e2\u8d2d") || text.contains("\u5238");
    }

    private boolean containsEvidenceSignal(String text) {
        return text.contains("\u8bc4\u4ef7") || text.contains("\u8bc4\u8bba") || text.contains("\u53e3\u7891")
                || text.contains("\u6392\u961f") || text.contains("\u73af\u5883") || text.contains("\u670d\u52a1");
    }

    private List<String> shopNames(List<DecisionRecommendation> shops) {
        List<String> names = new ArrayList<String>();
        for (DecisionRecommendation item : shops) names.add(item.getShopName());
        return names;
    }

    private boolean containsNormalizedShopName(String message, String shopName) {
        if (message == null || shopName == null) return false;
        String normalizedMessage = normalizeShopText(message);
        String normalizedName = normalizeShopText(shopName);
        return normalizedName.length() >= 2 && normalizedMessage.contains(normalizedName);
    }

    /**
     * Candidate ordinals are resolved from durable working memory before the tool planner sees the query.
     * "另一家" means the only candidate other than the focused shop when that relation is unambiguous.
     */
    private DecisionRecommendation resolveOrdinalReference(String message, AgentSessionContext context) {
        if (message == null || context.getCandidatePoolSnapshot() == null || context.getCandidatePoolSnapshot().isEmpty()) return null;
        List<DecisionRecommendation> candidates = context.getCandidatePoolSnapshot();
        if (message.contains("第一家") || message.contains("首选")) return candidates.get(0);
        if (message.contains("第三家") && candidates.size() >= 3) return candidates.get(2);
        if (message.contains("第二家")) return candidates.size() >= 2 ? candidates.get(1) : null;
        if (message.contains("另一家") || message.contains("另外一家")) {
            DecisionRecommendation focusedShop = focusedShop(context);
            if (focusedShop != null) {
                List<DecisionRecommendation> alternatives = new ArrayList<DecisionRecommendation>();
                for (DecisionRecommendation candidate : candidates) {
                    if (!focusedShop.getShopId().equals(candidate.getShopId())) alternatives.add(candidate);
                }
                if (alternatives.size() == 1) return alternatives.get(0);
            }
            return candidates.size() == 2 ? candidates.get(1) : null;
        }
        return null;
    }

    private DecisionRecommendation focusedShop(AgentSessionContext context) {
        if (context.getFocusedShopId() == null || context.getCandidatePoolSnapshot() == null) return null;
        for (DecisionRecommendation item : context.getCandidatePoolSnapshot()) {
            if (context.getFocusedShopId().equals(item.getShopId())) return item;
        }
        return null;
    }

    private boolean matchesShop(String message, String shopName) {
        if (message == null || shopName == null) return false;
        String normalizedMessage = normalizeShopText(message);
        String normalizedName = normalizeShopText(shopName);
        if (normalizedMessage.contains(normalizedName)) return true;
        for (int length = normalizedName.length(); length >= 2; length--) {
            for (int start = 0; start + length <= normalizedName.length(); start++) {
                if (normalizedMessage.contains(normalizedName.substring(start, start + length))) return true;
            }
        }
        return false;
    }

    private String normalizeShopText(String text) {
        return text.replaceAll("[（(].*?[）)]", "").replaceAll("[，。？?！!\\s]", "");
    }

    private boolean hasFocusedShopPronoun(String message, AgentSessionContext context) {
        if (message == null || context.getFocusedShopId() == null) return false;
        return message.contains("这家") || message.contains("这个店") || message.contains("那家")
                || message.contains("上一家") || message.contains("刚才那家");
    }

    /** A factual predicate can safely inherit the current focus without inventing a new restaurant request. */
    private boolean isImplicitFocusedFactQuery(String message) {
        if (message == null || message.trim().isEmpty()) return false;
        String[] factSignals = {"优惠", "券", "团购", "评价", "评论", "口碑", "新鲜", "营业", "开门", "地址", "位置", "环境", "包厢", "停车", "人均", "价格", "菜品", "招牌", "排队"};
        for (String signal : factSignals) {
            if (message.contains(signal)) return true;
        }
        return false;
    }

    private boolean isSingleShopTool(String toolName) {
        return "get_shop_detail".equals(toolName) || "query_shop_vouchers".equals(toolName)
                || "search_shop_evidence".equals(toolName);
    }

    private void materializeToolInput(Map<String, Object> input, String toolName, AgentSessionContext context,
                                      Long explicitlyReferencedShopId) {
        if (explicitlyReferencedShopId != null && isSingleShopTool(toolName)) input.put("shopId", explicitlyReferencedShopId);
        else if (isSingleShopTool(toolName) && !input.containsKey("shopId") && context.getFocusedShopId() != null) {
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

    private AgentConversationResponse ambiguousReferenceResponse(Long sessionId, AgentSessionContext context, List<String> names) {
        AgentConversationResponse response = new AgentConversationResponse();
        response.setSessionId(sessionId);
        response.setTurnNo(context.getTurnNo());
        response.setFocusedShopId(context.getFocusedShopId());
        response.setFocusedShopName(context.getFocusedShopName());
        response.setUsedModel(false);
        response.setAnswer("你说的商户可能是“" + String.join("”或“", names) + "”，请告诉我具体是哪一家，我再查询。");
        return response;
    }

    private static class ReferenceResolution {
        private final DecisionRecommendation shop;
        private final List<String> candidateNames;

        private ReferenceResolution(DecisionRecommendation shop, List<String> candidateNames) {
            this.shop = shop;
            this.candidateNames = candidateNames;
        }

        private boolean isAmbiguous() { return !candidateNames.isEmpty(); }
    }

    private static class ShopMention {
        private final int position;
        private final DecisionRecommendation shop;

        private ShopMention(int position, DecisionRecommendation shop) {
            this.position = position;
            this.shop = shop;
        }
    }

    private static class BoundFactTask {
        private final String toolName;
        private final DecisionRecommendation shop;

        private BoundFactTask(String toolName, DecisionRecommendation shop) {
            this.toolName = toolName;
            this.shop = shop;
        }

        private String arguments() { return "{\"shopId\":" + shop.getShopId() + "}"; }
        private String summary() { return toolName + "#" + shop.getShopId(); }
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("role", role); item.put("content", content);
        return item;
    }

    private void saveMessage(Long sessionId, String role, String type, String content) {
        // The chat boundary emits USER_INPUT and ASSISTANT_OUTPUT once per turn.
        // Do not duplicate the same content in a task-local message log.
    }

    private void ensureOwner(AiDecisionSession session) {
        if (session.getUserId() == null) return;
        if (UserHolder.getUser() == null || !session.getUserId().equals(UserHolder.getUser().getId())) {
            throw new SecurityException("无权访问其他用户的决策会话");
        }
    }

    private String compact(String value) {
        if (value == null) return "";
        String result = value.replaceAll("[\\r\\n\\t]+", " ");
        return result.length() > 1200 ? result.substring(0, 1200) + "..." : result;
    }

    private boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }

    private Long asLong(Object value) {
        try { return value == null ? null : Long.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private Integer asInteger(Object value) {
        try { return value == null ? null : Integer.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static class ToolPlanningResult {
        private final List<AgentToolResult> results;
        private final boolean usedModel;

        private ToolPlanningResult(List<AgentToolResult> results, boolean usedModel) {
            this.results = results;
            this.usedModel = usedModel;
        }
    }
}
