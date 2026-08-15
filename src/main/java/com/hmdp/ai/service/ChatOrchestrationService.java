package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.AgentConversationRequest;
import com.hmdp.ai.dto.ChatMessageRequest;
import com.hmdp.ai.dto.ChatMessageResponse;
import com.hmdp.ai.dto.DecisionFollowUpRequest;
import com.hmdp.ai.dto.DecisionRequest;
import com.hmdp.ai.dto.DecisionResponse;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChatOrchestrationService {
    @Resource private OpenAiCompatibleClient aiClient;
    @Resource private AiProperties aiProperties;
    @Resource private ConsumptionDecisionService decisionService;
    @Resource private AgentConversationService conversationService;
    @Resource private ObjectMapper objectMapper;

    public ChatMessageResponse chat(ChatMessageRequest request) {
        if (request == null || request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        String message = request.getMessage().trim();
        DecisionResponse activeDecision = request.getDecisionSessionId() == null ? null
                : decisionService.getDecision(request.getDecisionSessionId());
        String route = route(message, activeDecision == null ? "NONE" : activeDecision.getStatus());
        if ("GENERAL_CHAT".equals(route) && activeDecision != null
                && ("CLARIFYING".equals(activeDecision.getStatus()) || "WAITING_RELAXATION".equals(activeDecision.getStatus()))) {
            route = "EXIT_DECISION";
        }
        ChatMessageResponse response = new ChatMessageResponse();
        response.setRoute(route);
        response.setUsedModel(aiProperties.isConfigured());
        if ("START_DECISION".equals(route)) {
            DecisionRequest decisionRequest = new DecisionRequest();
            decisionRequest.setQuery(message);
            decisionRequest.setMaxCandidates(3);
            DecisionResponse decision = decisionService.decide(decisionRequest);
            response.setDecision(decision);
            response.setDecisionSessionId(decision.getSessionId());
            response.setAnswer(decision.getAnswer() == null ? decision.getQuestion() : decision.getAnswer());
            return response;
        }
        if ("BUSINESS_FOLLOW_UP".equals(route) && request.getDecisionSessionId() != null) {
            AgentConversationRequest followUp = new AgentConversationRequest();
            followUp.setMessage(message);
            response.setConversation(conversationService.converse(request.getDecisionSessionId(), followUp));
            response.setDecisionSessionId(request.getDecisionSessionId());
            response.setAnswer(response.getConversation().getAnswer());
            return response;
        }
        if ("EXIT_DECISION".equals(route) && activeDecision != null) {
            if ("CLARIFYING".equals(activeDecision.getStatus()) || "WAITING_RELAXATION".equals(activeDecision.getStatus())) {
                DecisionFollowUpRequest followUp = new DecisionFollowUpRequest();
                followUp.setMessage(message);
                decisionService.continueDecision(request.getDecisionSessionId(), followUp);
            }
            response.setDecisionSessionId(null);
        }
        if ("GENERAL_CHAT".equals(route)) response.setDecisionSessionId(null);
        response.setAnswer(generalReply(message));
        if (!aiProperties.isConfigured()) response.setDegradedReason("模型未配置，本次使用本地对话降级回复。");
        return response;
    }

    private String route(String message, String decisionStatus) {
        if (!aiProperties.isConfigured()) return fallbackRoute(message, decisionStatus);
        try {
            List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
            messages.add(message("system", "你是消费决策 Agent 的对话路由器。根据用户最新一句话选择路由：GENERAL_CHAT=普通闲聊或能力问答；START_DECISION=用户要餐饮推荐或新的消费决策；BUSINESS_FOLLOW_UP=围绕已推荐商户问优惠券、评价、备选或比较；EXIT_DECISION=用户正在补充推荐条件但改为闲聊、拒绝继续或明确结束。不要把普通闲聊路由到消费决策。"));
            messages.add(message("system", "当前决策状态=" + decisionStatus));
            messages.add(message("user", message));
            JsonNode result = aiClient.chatCompletion(messages, Arrays.asList(routeTool()), null, "CHAT_ROUTING");
            String arguments = result.path("choices").path(0).path("message").path("tool_calls").path(0)
                    .path("function").path("arguments").asText();
            return objectMapper.readTree(arguments).path("route").asText(fallbackRoute(message, decisionStatus));
        } catch (Exception ignored) {
            return fallbackRoute(message, decisionStatus);
        }
    }

    private String generalReply(String message) {
        if (!aiProperties.isConfigured()) return "你好，我是消费决策助手。想吃饭或需要了解已推荐商户时，随时告诉我。";
        try {
            return aiClient.chatText(Arrays.asList(
                    message("system", "你是点评消费决策助手。正常自然地进行简短闲聊；不要主动推荐商户、编造优惠或评价。用户表达消费需求时只提示可以继续描述需求。"),
                    message("user", message)), "GENERAL_CHAT");
        } catch (Exception ignored) {
            return "你好，我在。想聊聊吃什么、预算或用餐场景时，随时告诉我。";
        }
    }

    private String fallbackRoute(String message, String decisionStatus) {
        boolean hasDecision = !"NONE".equals(decisionStatus);
        if (message.contains("算了") || message.contains("不聊了") || message.contains("结束")) return hasDecision ? "EXIT_DECISION" : "GENERAL_CHAT";
        boolean consumption = message.contains("吃") || message.contains("餐") || message.contains("饭") || message.contains("推荐") || message.contains("附近") || message.contains("优惠") || message.contains("评价");
        if (!consumption) return "GENERAL_CHAT";
        return hasDecision && "COMPLETED".equals(decisionStatus) ? "BUSINESS_FOLLOW_UP" : "START_DECISION";
    }

    private Map<String, Object> routeTool() {
        Map<String, Object> route = new LinkedHashMap<String, Object>();
        route.put("type", "string");
        route.put("enum", Arrays.asList("GENERAL_CHAT", "START_DECISION", "BUSINESS_FOLLOW_UP", "EXIT_DECISION"));
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("route", route);
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put("type", "object"); parameters.put("properties", properties);
        parameters.put("required", Arrays.asList("route")); parameters.put("additionalProperties", false);
        Map<String, Object> function = new LinkedHashMap<String, Object>();
        function.put("name", "route_chat_message"); function.put("parameters", parameters);
        Map<String, Object> tool = new LinkedHashMap<String, Object>();
        tool.put("type", "function"); tool.put("function", function);
        return tool;
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("role", role); item.put("content", content); return item;
    }
}
