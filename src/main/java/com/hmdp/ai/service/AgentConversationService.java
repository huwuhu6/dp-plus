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
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentConversationService {
    private static final int MAX_TOOL_STEPS = 3;
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
            saveMessage(sessionId, "USER", "AGENT_FOLLOW_UP", request.getMessage().trim());
            List<AgentToolResult> results = runToolLoop(sessionId, context, request.getMessage().trim());
            boolean usedModel = aiProperties.isConfigured();
            if (results.isEmpty()) {
                results.add(runFallbackTool(sessionId, context.getTurnNo(), request.getMessage().trim(), context));
                usedModel = false;
            }
            for (AgentToolResult result : results) updateContext(context, result);
            session.setAgentContextJson(objectMapper.writeValueAsString(context));
            sessionMapper.updateById(session);

            AgentConversationResponse response = response(sessionId, context, results, usedModel);
            saveMessage(sessionId, "ASSISTANT", "AGENT_TOOL_ANSWER", response.getAnswer());
            return response;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("商户追问无法处理", e);
        }
    }

    private List<AgentToolResult> runToolLoop(Long sessionId, AgentSessionContext context, String userMessage) {
        List<AgentToolResult> results = new ArrayList<AgentToolResult>();
        if (!aiProperties.isConfigured()) return results;
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        messages.add(message("system", "你是点评消费决策 Agent 的工具规划器。必须只使用提供的只读工具取得商户事实；不得编造商户、券、评价或价格。每次最多调用一个工具。用户问商户事实时优先调用工具，不要直接回答。"));
        messages.add(message("system", "会话上下文：" + contextSummary(context)));
        messages.add(message("user", userMessage));
        for (int step = 0; step < MAX_TOOL_STEPS; step++) {
            JsonNode response;
            try {
                response = aiClient.chatCompletion(messages, toolRegistry.definitions(), null, "AGENT_TOOL_PLANNING");
            } catch (RuntimeException e) {
                break;
            }
            JsonNode assistant = response.path("choices").path(0).path("message");
            JsonNode calls = assistant.path("tool_calls");
            if (!calls.isArray() || calls.size() == 0) break;
            Map<String, Object> assistantMessage = objectMapper.convertValue(assistant, new TypeReference<Map<String, Object>>() { });
            messages.add(assistantMessage);
            JsonNode call = calls.get(0);
            String toolName = call.path("function").path("name").asText();
            String arguments = call.path("function").path("arguments").asText("{}");
            AgentToolResult result = executeTool(sessionId, context.getTurnNo(), toolName, arguments, context);
            results.add(result);
            Map<String, Object> toolMessage = message("tool", objectMapper.valueToTree(result.getFacts()).toString());
            toolMessage.put("tool_call_id", call.path("id").asText());
            messages.add(toolMessage);
        }
        return results;
    }

    private AgentToolResult executeTool(Long sessionId, Integer turnNo, String toolName, String arguments,
                                        AgentSessionContext context) {
        long startedAt = System.currentTimeMillis();
        AiAgentToolCall record = new AiAgentToolCall();
        record.setSessionId(sessionId); record.setTurnNo(turnNo); record.setToolName(toolName);
        record.setToolInputJson(arguments);
        try {
            Map<String, Object> input = objectMapper.readValue(arguments, new TypeReference<Map<String, Object>>() { });
            BaseAgentTool tool = toolRegistry.find(toolName);
            AgentToolResult result = tool.execute(input, context);
            result.setToolName(toolName);
            result.setDurationMs(System.currentTimeMillis() - startedAt);
            record.setStatus("SUCCESS");
            record.setToolOutputJson(objectMapper.writeValueAsString(result.getFacts()));
            record.setDurationMs(System.currentTimeMillis() - startedAt);
            toolCallMapper.insert(record);
            return result;
        } catch (Exception e) {
            record.setStatus("FAILED");
            record.setToolOutputJson("{\"error\":\"工具执行失败\"}");
            record.setDurationMs(System.currentTimeMillis() - startedAt);
            toolCallMapper.insert(record);
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
}
