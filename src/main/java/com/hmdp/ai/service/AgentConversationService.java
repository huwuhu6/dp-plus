package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.AgentConversationRequest;
import com.hmdp.ai.dto.AgentConversationResponse;
import com.hmdp.ai.dto.AgentSessionContext;
import com.hmdp.ai.dto.AgentToolTraceItem;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.entity.AiAgentToolCall;
import com.hmdp.ai.entity.AiDecisionMessage;
import com.hmdp.ai.entity.AiDecisionSession;
import com.hmdp.ai.mapper.AiAgentToolCallMapper;
import com.hmdp.ai.mapper.AiDecisionMessageMapper;
import com.hmdp.ai.mapper.AiDecisionSessionMapper;
import com.hmdp.ai.tool.AgentToolRegistry;
import com.hmdp.ai.tool.AgentToolResult;
import com.hmdp.ai.tool.BaseAgentTool;
import com.hmdp.utils.UserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentConversationService {
    private static final Logger log = LoggerFactory.getLogger(AgentConversationService.class);
    @Resource private AiDecisionSessionMapper sessionMapper;
    @Resource private AiDecisionMessageMapper messageMapper;
    @Resource private AiAgentToolCallMapper toolCallMapper;
    @Resource private AgentToolRegistry toolRegistry;
    @Resource private OpenAiCompatibleClient aiClient;
    @Resource private AiProperties aiProperties;
    @Resource private ObjectMapper objectMapper;

    public AgentConversationResponse converse(Long sessionId, AgentConversationRequest request) {
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
            AgentSessionContext context = loadContext(session);
            context.setTurnNo(context.getTurnNo() + 1);
            log.info("[AI][agent] event=TURN_START sessionId={} turnNo={} query={} context={}", sessionId,
                    context.getTurnNo(), compact(request.getMessage().trim()), compact(contextSummary(context)));
            saveMessage(sessionId, "USER", "AGENT_FOLLOW_UP", request.getMessage().trim());
            ToolPlanningResult planning = runToolLoop(sessionId, context, request.getMessage().trim());
            List<AgentToolResult> results = planning.results;
            boolean usedModel = planning.usedModel;
            if (results.isEmpty()) {
                results.add(runFallbackTool(sessionId, context.getTurnNo(), request.getMessage().trim(), context));
                usedModel = false;
            }
            for (AgentToolResult result : results) updateContext(context, result);
            session.setAgentContextJson(objectMapper.writeValueAsString(context));
            sessionMapper.updateById(session);

            AgentConversationResponse response = response(sessionId, context, results, usedModel);
            response.setAnswer(polishAnswer(request.getMessage().trim(), response.getAnswer(), usedModel));
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
        return toolCallMapper.selectList(new QueryWrapper<AiAgentToolCall>()
                .eq("session_id", sessionId).orderByAsc("turn_no").orderByAsc("id"));
    }

    private ToolPlanningResult runToolLoop(Long sessionId, AgentSessionContext context, String userMessage) {
        List<AgentToolResult> results = new ArrayList<AgentToolResult>();
        if (!aiProperties.isConfigured()) return new ToolPlanningResult(results, false);
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        messages.add(message("system", "你是点评消费决策 Agent 的工具规划器。必须只使用提供的只读工具取得商户事实；不得编造商户、券、评价或价格。每次最多调用一个工具。用户问商户事实时优先调用工具，不要直接回答。"));
        messages.add(message("system", "会话上下文：" + contextSummary(context)));
        messages.add(message("user", userMessage));
        try {
            JsonNode response = aiClient.chatCompletion(messages, toolRegistry.definitions(), null,
                    "AGENT_TOOL_PLANNING", aiProperties.getToolPlanningTimeoutMs());
            JsonNode calls = response.path("choices").path(0).path("message").path("tool_calls");
            if (!calls.isArray() || calls.size() == 0) {
                log.warn("[AI][agent] event=PLAN_EMPTY sessionId={} turnNo={} query={}", sessionId,
                        context.getTurnNo(), compact(userMessage));
                return new ToolPlanningResult(results, false);
            }
            JsonNode call = calls.get(0);
            String toolName = call.path("function").path("name").asText();
            String arguments = call.path("function").path("arguments").asText("{}");
            log.info("[AI][agent] event=TOOL_PLAN sessionId={} turnNo={} tool={} arguments={}", sessionId,
                    context.getTurnNo(), toolName, compact(arguments));
            results.add(executeTool(sessionId, context.getTurnNo(), toolName, arguments, context));
            return new ToolPlanningResult(results, true);
        } catch (RuntimeException e) {
            log.warn("[AI][agent] event=PLAN_FALLBACK sessionId={} turnNo={} query={} errorType={} detail={}", sessionId,
                    context.getTurnNo(), compact(userMessage), e.getClass().getSimpleName(), compact(e.getMessage()));
            return new ToolPlanningResult(results, false);
        }
    }

    private AgentToolResult executeTool(Long sessionId, Integer turnNo, String toolName, String arguments,
                                        AgentSessionContext context) {
        long startedAt = System.currentTimeMillis();
        AiAgentToolCall record = new AiAgentToolCall();
        record.setSessionId(sessionId); record.setTurnNo(turnNo); record.setToolName(toolName);
        record.setToolInputJson(arguments);
        try {
            log.info("[AI][agent] event=TOOL_START sessionId={} turnNo={} tool={} arguments={}", sessionId, turnNo,
                    toolName, compact(arguments));
            Map<String, Object> input = objectMapper.readValue(arguments, new TypeReference<Map<String, Object>>() { });
            BaseAgentTool tool = toolRegistry.find(toolName);
            AgentToolResult result = tool.execute(input, context);
            result.setToolName(toolName);
            result.setDurationMs(System.currentTimeMillis() - startedAt);
            record.setStatus("SUCCESS");
            record.setToolOutputJson(objectMapper.writeValueAsString(result.getFacts()));
            record.setDurationMs(System.currentTimeMillis() - startedAt);
            toolCallMapper.insert(record);
            log.info("[AI][agent] event=TOOL_SUCCESS sessionId={} turnNo={} tool={} durationMs={} result={}", sessionId,
                    turnNo, toolName, result.getDurationMs(), compact(record.getToolOutputJson()));
            return result;
        } catch (Exception e) {
            record.setStatus("FAILED");
            record.setToolOutputJson("{\"error\":\"工具执行失败\"}");
            record.setDurationMs(System.currentTimeMillis() - startedAt);
            toolCallMapper.insert(record);
            log.warn("[AI][agent] event=TOOL_FAILURE sessionId={} turnNo={} tool={} errorType={} detail={}", sessionId,
                    turnNo, toolName, e.getClass().getSimpleName(), compact(e.getMessage()));
            throw new IllegalArgumentException("工具 " + toolName + " 无法执行");
        }
    }

    private AgentToolResult runFallbackTool(Long sessionId, Integer turnNo, String message, AgentSessionContext context) {
        String toolName;
        if (message.contains("优惠") || message.contains("券")) toolName = "query_shop_vouchers";
        else if (message.contains("评价") || message.contains("评论") || message.contains("笔记") || message.contains("排队") || message.contains("环境")) toolName = "search_shop_evidence";
        else if (message.contains("还有") || message.contains("其他") || message.contains("换一家")) toolName = "search_alternative_shops";
        else toolName = "get_shop_detail";
        return executeTool(sessionId, turnNo, toolName, "{}", context);
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
            response.getToolTrace().add(new AgentToolTraceItem(result.getToolName(), result.getSummary(), result.getDurationMs()));
        }
        response.setAnswer(answer.toString());
        return response;
    }

    private String polishAnswer(String userMessage, String factualAnswer, boolean planningUsedModel) {
        if (!planningUsedModel || !Boolean.TRUE.equals(aiProperties.getNarrativeEnabled())) return factualAnswer;
        try {
            String answer = aiClient.chatText(java.util.Arrays.asList(
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

    private AgentSessionContext loadContext(AiDecisionSession session) throws Exception {
        AgentSessionContext context;
        if (session.getAgentContextJson() != null && !session.getAgentContextJson().trim().isEmpty()) {
            context = objectMapper.readValue(session.getAgentContextJson(), AgentSessionContext.class);
        } else {
            context = new AgentSessionContext();
            DecisionResponse decision = objectMapper.readValue(session.getResultJson(), DecisionResponse.class);
            for (DecisionRecommendation item : decision.getRecommendations()) context.getShownShopIds().add(item.getShopId());
            if (!decision.getRecommendations().isEmpty()) {
                DecisionRecommendation first = decision.getRecommendations().get(0);
                context.setFocusedShopId(first.getShopId()); context.setFocusedShopName(first.getShopName());
            }
        }
        return context;
    }

    private void updateContext(AgentSessionContext context, AgentToolResult result) {
        if (result.getFocusedShopId() != null) context.setFocusedShopId(result.getFocusedShopId());
        if (result.getFocusedShopName() != null) context.setFocusedShopName(result.getFocusedShopName());
    }

    private String contextSummary(AgentSessionContext context) {
        return "当前聚焦商户=" + (context.getFocusedShopId() == null ? "无" : context.getFocusedShopId() + ":" + context.getFocusedShopName())
                + "；已展示商户=" + context.getShownShopIds();
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("role", role); item.put("content", content);
        return item;
    }

    private void saveMessage(Long sessionId, String role, String type, String content) {
        AiDecisionMessage message = new AiDecisionMessage();
        message.setSessionId(sessionId); message.setRole(role); message.setMessageType(type); message.setContent(content);
        messageMapper.insert(message);
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

    private static class ToolPlanningResult {
        private final List<AgentToolResult> results;
        private final boolean usedModel;

        private ToolPlanningResult(List<AgentToolResult> results, boolean usedModel) {
            this.results = results;
            this.usedModel = usedModel;
        }
    }
}
