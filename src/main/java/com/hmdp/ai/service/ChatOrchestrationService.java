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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChatOrchestrationService {
    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrationService.class);
    @Resource private OpenAiCompatibleClient aiClient;
    @Resource private AiProperties aiProperties;
    @Resource private ConsumptionDecisionService decisionService;
    @Resource private AgentConversationService conversationService;
    @Resource private ChatMemoryService chatMemoryService;
    @Resource private ChatSessionStateService chatSessionStateService;
    @Resource private ObjectMapper objectMapper;

    public ChatMessageResponse chat(ChatMessageRequest request) {
        if (request == null || request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        String message = request.getMessage().trim();
        String chatId = chatMemoryService.resolveChatId(request.getChatId());
        List<Map<String, Object>> chatHistory = chatMemoryService.load(chatId);
        Long activeSessionId = resolveActiveSessionId(chatId, request.getDecisionSessionId());
        DecisionResponse activeDecision = activeSessionId == null ? null : decisionService.getDecision(activeSessionId);
        log.info("[AI][chat] event=MEMORY_LOADED chatId={} messages={}", chatId, chatHistory.size());
        log.info("[AI][chat] event=TURN_START chatId={} clientSessionId={} activeSessionId={} status={} query={}", chatId,
                request.getDecisionSessionId(), activeSessionId,
                activeDecision == null ? "NONE" : activeDecision.getStatus(), compact(message));
        String route = route(message, activeDecision == null ? "NONE" : activeDecision.getStatus(), chatHistory);
        log.info("[AI][chat] event=ROUTE_SELECTED chatId={} activeSessionId={} route={}", chatId, activeSessionId, route);
        ChatMessageResponse response = new ChatMessageResponse();
        response.setChatId(chatId);
        response.setRoute(route);
        response.setUsedModel(aiProperties.isConfigured());
        if ("START_DECISION".equals(route)) {
            DecisionRequest decisionRequest = new DecisionRequest();
            decisionRequest.setQuery(message);
            decisionRequest.setMaxCandidates(3);
            DecisionResponse decision = decisionService.decide(decisionRequest);
            response.setDecision(decision);
            response.setDecisionSessionId(decision.getSessionId());
            response.setDecisionStatus(decision.getStatus());
            response.setAnswer(decision.getAnswer() == null ? decision.getQuestion() : decision.getAnswer());
            chatSessionStateService.activate(chatId, decision.getSessionId());
            recordTurn(chatId, message, response);
            return response;
        }
        if ("BUSINESS_FOLLOW_UP".equals(route)) {
            Long followUpSessionId = resolveFollowUpSessionId(chatId, activeSessionId);
            if (followUpSessionId == null) {
                response.setAnswer("我没有找到可以关联的推荐会话。请告诉我店名，或者重新说一下你的用餐需求，我会先查询再回答。");
                recordTurn(chatId, message, response);
                return response;
            }
            DecisionResponse followUpDecision = decisionService.getDecision(followUpSessionId);
            if (!"COMPLETED".equals(followUpDecision.getStatus())) {
                response.setDecisionSessionId(followUpSessionId);
                response.setDecisionStatus(followUpDecision.getStatus());
                response.setAnswer("刚才的推荐还在等待补充条件，完成推荐后我才能查询具体商户的评价、优惠券或备选。");
                recordTurn(chatId, message, response);
                return response;
            }
            AgentConversationRequest followUp = new AgentConversationRequest();
            followUp.setMessage(message);
            response.setConversation(conversationService.converse(followUpSessionId, followUp));
            response.setDecisionSessionId(followUpSessionId);
            response.setDecisionStatus(followUpDecision.getStatus());
            response.setAnswer(response.getConversation().getAnswer());
            recordTurn(chatId, message, response);
            return response;
        }
        if ("EXIT_DECISION".equals(route) && activeDecision != null) {
            if ("CLARIFYING".equals(activeDecision.getStatus()) || "WAITING_RELAXATION".equals(activeDecision.getStatus())) {
                DecisionFollowUpRequest followUp = new DecisionFollowUpRequest();
                followUp.setMessage(message);
                decisionService.continueDecision(activeSessionId, followUp);
            }
            chatSessionStateService.clearActive(chatId);
            response.setDecisionSessionId(null);
        }
        if ("GENERAL_CHAT".equals(route) && activeDecision != null) {
            response.setDecisionSessionId(activeSessionId);
            response.setDecisionStatus(activeDecision.getStatus());
        }
        response.setAnswer(generalReply(message, chatHistory));
        if (!aiProperties.isConfigured()) {
            response.setUsedModel(false);
            response.setDegradedReason("模型服务未配置：当前后端进程没有读取到 DEEPSEEK_API_KEY，本次使用本地对话降级回复。");
            log.warn("[AI][chat] action=GENERAL_CHAT event=MODEL_NOT_CONFIGURED");
        }
        recordTurn(chatId, message, response);
        return response;
    }

    private String route(String message, String decisionStatus, List<Map<String, Object>> chatHistory) {
        if (!aiProperties.isConfigured()) return fallbackRoute(message, decisionStatus);
        try {
            List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
            messages.add(message("system", "你是消费决策 Agent 的对话路由器。当前业务只支持餐饮商户的消费决策。根据用户最新一句话选择路由：GENERAL_CHAT=普通闲聊、能力问答、非餐饮需求或需求不完整；START_DECISION=用户明确要找餐厅、吃饭、菜品、订餐或餐饮消费推荐；BUSINESS_FOLLOW_UP=围绕已推荐餐饮商户问优惠券、评价、备选或比较；EXIT_DECISION=用户明确说算了、不找了、结束或不需要了。‘附近有啥’、‘有什么推荐’这类未说明餐饮意图的句子必须是 GENERAL_CHAT，先自然追问想找什么，不能擅自开始餐饮检索。游泳、健身、运动场馆、医院、景点、住宿、交通等即使包含‘附近’也必须是 GENERAL_CHAT，绝不能进入餐饮推荐。"));
            messages.add(message("system", "当前决策状态=" + decisionStatus));
            messages.addAll(chatHistory);
            messages.add(message("user", message));
            JsonNode result = aiClient.chatCompletion(messages, Arrays.asList(routeTool()), null, "CHAT_ROUTING");
            String arguments = result.path("choices").path(0).path("message").path("tool_calls").path(0)
                    .path("function").path("arguments").asText();
            String route = objectMapper.readTree(arguments).path("route").asText(fallbackRoute(message, decisionStatus));
            log.info("[AI][chat] action=CHAT_ROUTING event=MODEL_ROUTE route={}", route);
            return route;
        } catch (Exception e) {
            log.warn("[AI][chat] action=CHAT_ROUTING event=FALLBACK errorType={}", e.getClass().getSimpleName());
            return fallbackRoute(message, decisionStatus);
        }
    }

    private String generalReply(String message, List<Map<String, Object>> chatHistory) {
        if (!aiProperties.isConfigured()) return "你好，我是消费决策助手。想吃饭或需要了解已推荐商户时，随时告诉我。";
        try {
            List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
            messages.add(message("system", "你是本地餐饮消费决策助手。基于对话上下文自然、简短地回答。仅支持餐厅、用餐、菜品、餐饮优惠和已推荐餐饮商户的事实查询；面对游泳、运动场馆、医疗、住宿、交通等非餐饮需求，要友好说明当前暂不具备对应数据和检索能力，不得编造或推荐餐饮商户。"));
            messages.addAll(chatHistory);
            messages.add(message("user", message));
            String answer = aiClient.chatText(messages, "GENERAL_CHAT");
            if (containsUngroundedRecommendation(answer)) {
                log.warn("[AI][chat] action=GENERAL_CHAT event=UNGROUNDED_RECOMMENDATION_BLOCKED");
                return "抱歉，我不能在没有检索到本地商户数据的情况下直接列出餐厅或消费建议。你可以告诉我想吃什么、预算和地点，我会先查询餐饮数据后再推荐。";
            }
            return answer;
        } catch (Exception e) {
            log.warn("[AI][chat] action=GENERAL_CHAT event=FALLBACK errorType={}", e.getClass().getSimpleName());
            return "你好，我在。想聊聊吃什么、预算或用餐场景时，随时告诉我。";
        }
    }

    private String fallbackRoute(String message, String decisionStatus) {
        boolean hasDecision = !"NONE".equals(decisionStatus);
        if (message.contains("算了") || message.contains("不聊了") || message.contains("结束")) return hasDecision ? "EXIT_DECISION" : "GENERAL_CHAT";
        boolean dining = message.contains("吃") || message.contains("餐厅") || message.contains("饭") || message.contains("菜") || message.contains("订餐");
        if (!dining) return "GENERAL_CHAT";
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
        function.put("name", "route_chat_message");
        function.put("description", "仅当用户明确需要餐饮推荐或已推荐餐饮商户的事实查询时，才选择餐饮决策相关路由。");
        function.put("parameters", parameters);
        Map<String, Object> tool = new LinkedHashMap<String, Object>();
        tool.put("type", "function"); tool.put("function", function);
        return tool;
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("role", role); item.put("content", content); return item;
    }

    private String compact(String value) {
        if (value == null) return "";
        String result = value.replaceAll("[\\r\\n\\t]+", " ");
        return result.length() > 800 ? result.substring(0, 800) + "..." : result;
    }

    private void recordTurn(String chatId, String userMessage, ChatMessageResponse response) {
        chatMemoryService.appendTurn(chatId, userMessage, response.getAnswer(), response.getRoute(), response.getDecisionSessionId());
        log.info("[AI][chat] event=MEMORY_SAVED chatId={} userChars={} assistantChars={}", chatId,
                userMessage.length(), response.getAnswer() == null ? 0 : response.getAnswer().length());
    }

    private Long resolveActiveSessionId(String chatId, Long clientSessionId) {
        com.hmdp.ai.entity.AiChatSession state = chatSessionStateService.get(chatId);
        if (state != null && state.getActiveDecisionSessionId() != null) {
            if (clientSessionId != null && !clientSessionId.equals(state.getActiveDecisionSessionId())) {
                log.warn("[AI][chat] event=CLIENT_SESSION_IGNORED chatId={} clientSessionId={} activeSessionId={}", chatId,
                        clientSessionId, state.getActiveDecisionSessionId());
            }
            return state.getActiveDecisionSessionId();
        }
        return clientSessionId;
    }

    private Long resolveFollowUpSessionId(String chatId, Long activeSessionId) {
        if (activeSessionId != null) return activeSessionId;
        com.hmdp.ai.entity.AiChatSession state = chatSessionStateService.get(chatId);
        Long sessionId = state == null ? null : state.getLastDecisionSessionId();
        if (sessionId == null) sessionId = chatMemoryService.findLatestDecisionSessionId(chatId);
        if (sessionId != null) chatSessionStateService.rememberLast(chatId, sessionId);
        log.info("[AI][chat] event=FOLLOW_UP_CONTEXT_RESOLVED chatId={} sessionId={} source={}", chatId, sessionId,
                activeSessionId != null ? "ACTIVE" : (state == null ? "MESSAGE_HISTORY" : "LAST"));
        return sessionId;
    }

    private boolean containsUngroundedRecommendation(String answer) {
        if (answer == null) return false;
        boolean numberedList = answer.matches("(?s).*\\n?\\s*1[.、].*");
        boolean businessMention = answer.contains("餐厅") || answer.contains("餐馆") || answer.contains("饭店");
        return numberedList && businessMention;
    }
}
