package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.client.SpringAiTextClient;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.AgentConversationRequest;
import com.hmdp.ai.dto.ChatMessageRequest;
import com.hmdp.ai.dto.ChatMessageResponse;
import com.hmdp.ai.dto.DecisionFollowUpRequest;
import com.hmdp.ai.dto.DecisionRequest;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.dto.ConversationLocationSlot;
import com.hmdp.ai.dto.ResolvedLocationCandidate;
import com.hmdp.ai.entity.AiChatSession;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChatOrchestrationService {
    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrationService.class);
    @Resource private OpenAiCompatibleClient aiClient;
    @Resource private SpringAiTextClient springAiTextClient;
    @Resource private AiProperties aiProperties;
    @Resource private ConsumptionDecisionService decisionService;
    @Resource private AgentConversationService conversationService;
    @Resource private ChatMemoryService chatMemoryService;
    @Resource private ConversationStateService conversationStateService;
    @Resource private AmapMcpLocationResolutionService locationResolutionService;
    @Resource private ObjectMapper objectMapper;

    public ChatMessageResponse chat(ChatMessageRequest request) {
        if (request == null || request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        String message = request.getMessage().trim();
        String chatId = chatMemoryService.resolveChatId(request.getChatId());
        List<Map<String, Object>> chatHistory = chatMemoryService.load(chatId);
        AiChatSession state = conversationStateService.getOrCreate(chatId);
        if (request.getLocation() != null) conversationStateService.acceptLocation(state, request.getLocation());
        Long activeSessionId = resolveActiveSessionId(state, request.getDecisionSessionId());
        DecisionResponse activeDecision = activeSessionId == null ? null : decisionService.getDecision(activeSessionId);
        log.info("[AI][chat] event=MEMORY_LOADED chatId={} messages={}", chatId, chatHistory.size());
        log.info("[AI][chat] event=TURN_START chatId={} clientSessionId={} activeSessionId={} status={} query={}", chatId,
                request.getDecisionSessionId(), activeSessionId,
                activeDecision == null ? "NONE" : activeDecision.getStatus(), compact(message));
        if (request.getSelectedOptionId() != null && activeDecision != null
                && ("CLARIFYING".equals(activeDecision.getStatus()) || "WAITING_RELAXATION".equals(activeDecision.getStatus()))) {
            ChatMessageResponse eventResponse = new ChatMessageResponse();
            eventResponse.setChatId(chatId);
            eventResponse.setRoute("DECISION_EVENT");
            eventResponse.setUsedModel(false);
            return handleDecisionEvent(chatId, message, request, state, activeSessionId, eventResponse);
        }
        if (isLocationClarification(activeDecision) && isPotentialNamedLocation(message)) {
            ChatMessageResponse locationResponse = resolveNamedLocation(chatId, message, state, activeSessionId, activeDecision);
            if (locationResponse != null) return locationResponse;
        }
        if (isPausedDecision(activeDecision) && request.getSelectedOptionId() == null && isNewRecommendationIntent(message)) {
            DecisionFollowUpRequest cancel = new DecisionFollowUpRequest();
            cancel.setSelectedOptionId("END_DECISION");
            cancel.setMessage("被新的餐饮需求替代：" + message);
            decisionService.continueDecision(activeSessionId, cancel);
            conversationStateService.clearActiveDecision(state);
            log.info("[AI][chat] event=PENDING_DECISION_SUPERSEDED chatId={} previousSessionId={} query={}",
                    chatId, activeSessionId, compact(message));
            log.info("[AI][chat] event=ROUTE_SELECTED chatId={} activeSessionId={} route=START_DECISION source=PENDING_SUPERSEDE",
                    chatId, activeSessionId);
            return startDecision(chatId, message, state, false);
        }
        if (isNewRecommendationIntent(message)) {
            log.info("[AI][chat] event=ROUTE_GUARD_MATCHED chatId={} activeSessionId={} route=START_DECISION source=NEW_RECOMMENDATION query={}",
                    chatId, activeSessionId, compact(message));
            return startDecision(chatId, message, state, aiProperties.isConfigured());
        }
        String route = resolveContextualFollowUpRoute(chatId, message, state, activeSessionId, activeDecision);
        if (route == null) route = route(message, activeDecision == null ? "NONE" : activeDecision.getStatus(), chatHistory);
        log.info("[AI][chat] event=ROUTE_SELECTED chatId={} activeSessionId={} route={}", chatId, activeSessionId, route);
        ChatMessageResponse response = new ChatMessageResponse();
        response.setChatId(chatId);
        response.setRoute(route);
        response.setUsedModel(aiProperties.isConfigured());
        if ("START_DECISION".equals(route)) {
            return startDecision(chatId, message, state, aiProperties.isConfigured());
        }
        if ("BUSINESS_FOLLOW_UP".equals(route)) {
            Long followUpSessionId = resolveFollowUpSessionId(chatId, state, activeSessionId);
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
            conversationStateService.clearActiveDecision(state);
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

    private ChatMessageResponse startDecision(String chatId, String message, AiChatSession state, boolean usedModel) {
        DecisionRequest decisionRequest = new DecisionRequest();
        decisionRequest.setQuery(message);
        decisionRequest.setMaxCandidates(3);
        String explicitLocationScope = extractExplicitLocationScope(message);
        if (explicitLocationScope == null) {
            applyLocationSlot(decisionRequest, state);
        } else {
            // A location named in this turn always takes precedence over the previous browser location.
            decisionRequest.setLocationStatus("MISSING");
            log.info("[AI][chat] event=EXPLICIT_LOCATION_SCOPE_DETECTED chatId={} scope={} action=SKIP_LOCATION_SLOT_REUSE",
                    chatId, compact(explicitLocationScope));
        }
        DecisionResponse decision = decisionService.decide(decisionRequest);
        conversationStateService.activateDecision(state, decision.getSessionId());
        if (explicitLocationScope != null && isLocationClarification(decision)) {
            ChatMessageResponse locationResponse = buildLocationResolutionResponse(chatId, message, state,
                    decision.getSessionId(), decision, explicitLocationScope);
            if (locationResponse != null) return locationResponse;
        }
        ChatMessageResponse response = new ChatMessageResponse();
        response.setChatId(chatId);
        response.setRoute("START_DECISION");
        response.setUsedModel(usedModel);
        response.setDecision(decision);
        response.setDecisionSessionId(decision.getSessionId());
        response.setDecisionStatus(decision.getStatus());
        response.setAnswer(decision.getAnswer() == null ? decision.getQuestion() : decision.getAnswer());
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
            String answer = springAiTextClient.chatText(messages, "GENERAL_CHAT");
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

    private String resolveContextualFollowUpRoute(String chatId, String message, AiChatSession state,
                                                  Long activeSessionId, DecisionResponse activeDecision) {
        Long sessionId = resolveFollowUpSessionId(chatId, state, activeSessionId);
        if (sessionId == null || hasExplicitNewDecisionIntent(message)) return null;
        DecisionResponse decision = activeDecision;
        if (decision == null || !sessionId.equals(activeSessionId)) decision = decisionService.getDecision(sessionId);
        if (decision == null || !"COMPLETED".equals(decision.getStatus())) return null;
        if (!conversationService.hasCandidateReference(sessionId, message)) return null;
        log.info("[AI][chat] event=ROUTE_GUARD_MATCHED chatId={} sessionId={} route=BUSINESS_FOLLOW_UP query={}",
                chatId, sessionId, compact(message));
        return "BUSINESS_FOLLOW_UP";
    }

    private boolean hasExplicitNewDecisionIntent(String message) {
        return message.contains("我想吃") || message.contains("想找") || message.contains("帮我找")
                || message.contains("给我推荐") || message.contains("重新推荐") || message.contains("再推荐")
                || isNewRecommendationIntent(message);
    }

    private boolean isNewRecommendationIntent(String message) {
        if (message == null || message.isEmpty() || isFocusedShopQuestion(message)) return false;
        boolean asksForPlace = message.contains("店") || message.contains("餐厅") || message.contains("餐馆")
                || message.contains("地方") || message.contains("吃饭");
        boolean asksForNewOptions = message.contains("有没有") || message.contains("有没") || message.contains("还有")
                || message.contains("推荐") || message.contains("找") || message.contains("来一家");
        boolean hasScene = message.contains("适合约会") || message.contains("约会") || message.contains("聚餐")
                || message.contains("安静") || message.contains("清淡") || message.contains("性价比");
        boolean hasDiningCategory = message.contains("烤肉") || message.contains("烧烤") || message.contains("火锅")
                || message.contains("日料") || message.contains("料理") || message.contains("小吃") || message.contains("咖啡")
                || message.contains("奶茶") || message.contains("川菜") || message.contains("粤菜");
        return asksForPlace && (asksForNewOptions || hasScene || hasDiningCategory);
    }

    private boolean isFocusedShopQuestion(String message) {
        return message.contains("这家") || message.contains("那家") || message.contains("这一个")
                || message.contains("上一家") || message.contains("刚才那家");
    }

    private boolean isPausedDecision(DecisionResponse decision) {
        return decision != null && ("CLARIFYING".equals(decision.getStatus())
                || "WAITING_RELAXATION".equals(decision.getStatus()));
    }

    private boolean isLocationClarification(DecisionResponse decision) {
        return decision != null && "CLARIFYING".equals(decision.getStatus());
    }

    private boolean isPotentialNamedLocation(String message) {
        if (!locationResolutionService.isAvailable()) return false;
        if (message.length() > 40 || message.contains("?") || message.contains("？")) return false;
        return !message.contains("算了") && !message.contains("结束") && !message.contains("不找了");
    }

    private ChatMessageResponse resolveNamedLocation(String chatId, String message, AiChatSession state,
                                                     Long activeSessionId, DecisionResponse activeDecision) {
        return buildLocationResolutionResponse(chatId, message, state, activeSessionId, activeDecision, message);
    }

    private ChatMessageResponse buildLocationResolutionResponse(String chatId, String message, AiChatSession state,
                                                                Long activeSessionId, DecisionResponse activeDecision,
                                                                String locationQuery) {
        if (!locationServiceAvailable()) return null;
        List<ResolvedLocationCandidate> candidates = locationResolutionService.resolve(locationQuery);
        if (candidates.isEmpty()) {
            log.info("[AI][chat] event=LOCATION_RESOLUTION_EMPTY chatId={} sessionId={} query={}",
                    chatId, activeSessionId, compact(locationQuery));
            return null;
        }
        conversationStateService.rememberLocationCandidates(state, candidates);
        DecisionResponse decision = activeDecision;
        decision.setQuestion(buildLocationConfirmationQuestion(candidates));
        decision.setAnswer(null);
        decision.getOptions().clear();
        for (int index = 0; index < candidates.size(); index++) {
            decision.getOptions().add(new com.hmdp.ai.dto.DecisionOption("CONFIRM_RESOLVED_LOCATION_" + index,
                    "使用“" + candidates.get(index).getLabel() + "”作为搜索位置"));
        }
        decision.getOptions().add(new com.hmdp.ai.dto.DecisionOption("PROVIDE_LOCATION", "改用当前位置坐标"));
        decision.getOptions().add(new com.hmdp.ai.dto.DecisionOption("DECLINE_LOCATION", "不提供位置，按全城搜索"));
        decision.getOptions().add(new com.hmdp.ai.dto.DecisionOption("END_DECISION", "结束本次推荐"));
        ChatMessageResponse response = new ChatMessageResponse();
        response.setChatId(chatId);
        response.setRoute("LOCATION_RESOLUTION");
        response.setUsedModel(false);
        response.setDecision(decision);
        response.setDecisionSessionId(activeSessionId);
        response.setDecisionStatus(decision.getStatus());
        response.setAnswer(decision.getQuestion());
        log.info("[AI][chat] event=LOCATION_RESOLUTION_CANDIDATES chatId={} sessionId={} query={} candidates={}",
                chatId, activeSessionId, compact(locationQuery), candidates.size());
        recordTurn(chatId, message, response);
        return response;
    }

    private boolean locationServiceAvailable() {
        return locationResolutionService != null && locationResolutionService.isAvailable();
    }

    private String extractExplicitLocationScope(String message) {
        if (!locationServiceAvailable()) return null;
        String normalized = message.replaceAll("\\s+", "").trim();
        if (normalized.length() > 80) return null;
        Pattern relativePlace = Pattern.compile("(?:^|[，,。；;]|在|去|到)([\\p{IsHan}]{2,12}?)(?:那边|附近|周边|一带|当地)");
        Matcher relativeMatcher = relativePlace.matcher(normalized);
        if (relativeMatcher.find()) return relativeMatcher.group(1);
        Pattern administrativePlace = Pattern.compile("([\\p{IsHan}]{2,12}(?:省|市|区|县|镇|乡|街道|大学城|商圈))");
        Matcher administrativeMatcher = administrativePlace.matcher(normalized);
        if (administrativeMatcher.find()) return administrativeMatcher.group(1);
        return null;
    }

    private String buildLocationConfirmationQuestion(List<ResolvedLocationCandidate> candidates) {
        if (candidates.size() == 1) {
            ResolvedLocationCandidate candidate = candidates.get(0);
            return "已通过地图服务解析到“" + candidate.getLabel() + "”。确认使用该位置搜索附近餐饮商户吗？";
        }
        return "地图服务解析到多个可能地点，请选择要作为搜索中心的位置。";
    }

    private boolean isRestaurantSearch(String message) {
        String[] keywords = {"吃", "餐厅", "餐馆", "饭店", "饭", "菜", "烧烤", "烤肉", "火锅", "日料", "料理", "小吃", "咖啡", "奶茶"};
        for (String keyword : keywords) {
            if (message.contains(keyword)) return true;
        }
        return false;
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

    private ChatMessageResponse handleDecisionEvent(String chatId, String message, ChatMessageRequest request,
                                                    AiChatSession state, Long activeSessionId,
                                                    ChatMessageResponse response) {
        String optionId = request.getSelectedOptionId();
        if (optionId.startsWith("CONFIRM_RESOLVED_LOCATION_")) {
            int index = Integer.parseInt(optionId.substring("CONFIRM_RESOLVED_LOCATION_".length()));
            ResolvedLocationCandidate candidate = conversationStateService.acceptPendingLocation(state, index);
            optionId = "PROVIDE_LOCATION";
            log.info("[AI][chat] event=LOCATION_RESOLUTION_CONFIRMED chatId={} sessionId={} label={} latitude={} longitude={}",
                    chatId, activeSessionId, candidate.getLabel(), candidate.getLatitude(), candidate.getLongitude());
        }
        if ("DECLINE_LOCATION".equals(optionId)) conversationStateService.declineLocation(state);
        DecisionFollowUpRequest followUp = new DecisionFollowUpRequest();
        followUp.setSelectedOptionId(optionId);
        followUp.setMessage(message);
        if ("PROVIDE_LOCATION".equals(optionId)) {
            ConversationLocationSlot location = conversationStateService.usableLocation(state);
            if (location == null) throw new IllegalArgumentException("当前没有有效位置，请重新授权定位后继续");
            followUp.setLatitude(location.getLatitude());
            followUp.setLongitude(location.getLongitude());
        }
        log.info("[AI][chat] event=DECISION_EVENT chatId={} sessionId={} optionId={}", chatId, activeSessionId, optionId);
        DecisionResponse decision = decisionService.continueDecision(activeSessionId, followUp);
        response.setDecision(decision);
        response.setDecisionSessionId("CANCELLED".equals(decision.getStatus()) ? null : decision.getSessionId());
        response.setDecisionStatus(decision.getStatus());
        response.setAnswer(decision.getAnswer() == null ? decision.getQuestion() : decision.getAnswer());
        if ("CANCELLED".equals(decision.getStatus())) conversationStateService.clearActiveDecision(state);
        else conversationStateService.activateDecision(state, decision.getSessionId());
        recordTurn(chatId, message, response);
        return response;
    }

    private void applyLocationSlot(DecisionRequest request, AiChatSession state) {
        ConversationLocationSlot location = conversationStateService.usableLocation(state);
        if (location != null) {
            request.setLatitude(location.getLatitude());
            request.setLongitude(location.getLongitude());
            request.setProvince(location.getProvince());
            request.setCity(location.getCity());
            request.setDistrict(location.getDistrict());
            request.setLocationStatus("AVAILABLE");
            request.setUseLocationScope(true);
            log.info("[AI][chat] event=SLOT_REUSED chatId={} slot=location source={} latitude={} longitude={}",
                    state.getChatId(), location.getSource(), location.getLatitude(), location.getLongitude());
            return;
        }
        String status = conversationStateService.slots(state).getLocation().getStatus();
        request.setLocationStatus(status == null ? "MISSING" : status);
        log.info("[AI][chat] event=SLOT_READ chatId={} slot=location status={}", state.getChatId(), request.getLocationStatus());
    }

    private Long resolveActiveSessionId(AiChatSession state, Long clientSessionId) {
        if (state.getActiveDecisionSessionId() != null) {
            if (clientSessionId != null && !clientSessionId.equals(state.getActiveDecisionSessionId())) {
                log.warn("[AI][chat] event=CLIENT_SESSION_IGNORED chatId={} clientSessionId={} activeSessionId={}", state.getChatId(),
                        clientSessionId, state.getActiveDecisionSessionId());
            }
            return state.getActiveDecisionSessionId();
        }
        return clientSessionId;
    }

    private Long resolveFollowUpSessionId(String chatId, AiChatSession state, Long activeSessionId) {
        if (activeSessionId != null) return activeSessionId;
        Long sessionId = state.getLastDecisionSessionId();
        boolean restoredFromHistory = false;
        if (sessionId == null) {
            sessionId = chatMemoryService.findLatestDecisionSessionId(chatId);
            restoredFromHistory = sessionId != null && sessionId > 0;
        }
        if (sessionId != null && sessionId <= 0) sessionId = null;
        if (restoredFromHistory) conversationStateService.rememberLastDecision(state, sessionId);
        log.info("[AI][chat] event=FOLLOW_UP_CONTEXT_RESOLVED chatId={} sessionId={} source={}", chatId, sessionId,
                activeSessionId != null ? "ACTIVE" : (state.getLastDecisionSessionId() == null ? "MESSAGE_HISTORY" : "LAST"));
        return sessionId;
    }

    private boolean containsUngroundedRecommendation(String answer) {
        if (answer == null) return false;
        boolean numberedList = answer.matches("(?s).*\\n?\\s*1[.、].*");
        boolean businessMention = answer.contains("餐厅") || answer.contains("餐馆") || answer.contains("饭店");
        return numberedList && businessMention;
    }
}
