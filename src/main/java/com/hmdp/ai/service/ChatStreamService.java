package com.hmdp.ai.service;

import com.hmdp.ai.dto.AgentConversationResponse;
import com.hmdp.ai.dto.ChatMessageRequest;
import com.hmdp.ai.dto.ChatMessageResponse;
import com.hmdp.ai.dto.ChatStreamEventData;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Translates the completed, auditable chat result into a small SSE protocol.
 * Business orchestration remains in ChatOrchestrationService, keeping the
 * synchronous endpoint and all existing evaluation trajectories compatible.
 */
@Service
public class ChatStreamService {
    private static final long STREAM_TIMEOUT_MS = 75_000L;
    private static final int TEXT_DELTA_CHARS = 18;

    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Resource private ChatOrchestrationService chatOrchestrationService;
    @Resource private IdempotencyService idempotencyService;

    public SseEmitter stream(ChatMessageRequest request, UserDTO user) {
        return stream(request, user, null);
    }

    public SseEmitter stream(ChatMessageRequest request, UserDTO user, String idempotencyKey) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        streamExecutor.execute(() -> runStream(emitter, request, user, idempotencyKey));
        return emitter;
    }

    private void runStream(SseEmitter emitter, ChatMessageRequest request, UserDTO user, String idempotencyKey) {
        try {
            if (user != null) UserHolder.saveUser(user);
            sendStatus(emitter, "REQUEST_ACCEPTED", "已收到消息，正在整理本轮对话上下文");
            sendStatus(emitter, "ORCHESTRATING", "正在执行餐饮决策与业务工具查询");
            AtomicBoolean modelTextStreamed = new AtomicBoolean(false);
            java.util.function.Consumer<String> textConsumer = chunk -> {
                modelTextStreamed.set(true);
                sendModelTextDelta(emitter, chunk);
            };
            java.util.function.Consumer<ChatStreamEventData> eventConsumer = event -> sendPipelineEvent(emitter, event);
            String chatId = idempotencyKey == null || idempotencyKey.trim().isEmpty() ? null
                    : chatOrchestrationService.resolveChatId(request);
            ChatMessageResponse response = idempotencyService == null || idempotencyKey == null || idempotencyKey.trim().isEmpty()
                    ? chatOrchestrationService.chat(request, textConsumer, eventConsumer)
                    : idempotencyService.execute(com.hmdp.ai.runtime.IdempotencyScope.CHAT_MESSAGE, chatId, idempotencyKey, request,
                    ChatMessageResponse.class, () -> chatOrchestrationService.chat(request, textConsumer, eventConsumer));
            sendStatus(emitter, "RESULT_READY", "已获得可展示的业务结果");
            if (!modelTextStreamed.get()) sendTextDeltas(emitter, response.getAnswer());
            sendRecommendationComponent(emitter, response);
            sendSuggestedChips(emitter, response);
            ChatStreamEventData complete = new ChatStreamEventData();
            complete.setStage("COMPLETED");
            complete.setResponse(response);
            send(emitter, "complete", complete);
            emitter.complete();
        } catch (Exception e) {
            ChatStreamEventData error = new ChatStreamEventData();
            error.setStage("FAILED");
            error.setMessage("本次请求处理失败，请稍后重试。");
            error.getMetadata().put("errorType", e.getClass().getSimpleName());
            try {
                send(emitter, "error", error);
                emitter.complete();
            } catch (Exception ignored) {
                emitter.completeWithError(e);
            }
        } finally {
            UserHolder.removeUser();
        }
    }

    private void sendRecommendationComponent(SseEmitter emitter, ChatMessageResponse response) throws IOException {
        DecisionResponse decision = response.getDecision();
        if (decision == null || decision.getRecommendations() == null || decision.getRecommendations().isEmpty()) return;
        List<Map<String, Object>> cards = new ArrayList<Map<String, Object>>();
        int index = 1;
        for (DecisionRecommendation recommendation : decision.getRecommendations()) {
            Map<String, Object> card = new LinkedHashMap<String, Object>();
            card.put("index", index++);
            card.put("shopId", recommendation.getShopId());
            card.put("shopName", recommendation.getShopName());
            card.put("avgPrice", recommendation.getAvgPrice());
            card.put("distanceKm", recommendation.getDistanceKm());
            card.put("score", recommendation.getScore());
            card.put("address", recommendation.getAddress());
            card.put("openHours", recommendation.getOpenHours());
            card.put("matchedReasons", recommendation.getMatchedReasons());
            cards.add(card);
        }
        ChatStreamEventData component = new ChatStreamEventData();
        component.setComponentType("SHOP_RECOMMENDATION_LIST");
        component.setPayload(cards);
        send(emitter, "ui_component", component);
    }

    private void sendSuggestedChips(SseEmitter emitter, ChatMessageResponse response) throws IOException {
        List<Map<String, String>> actions = suggestedChips(response);
        if (actions.isEmpty()) return;
        ChatStreamEventData chips = new ChatStreamEventData();
        chips.setPayload(actions);
        send(emitter, "suggested_chips", chips);
    }

    List<Map<String, String>> suggestedChips(ChatMessageResponse response) {
        List<Map<String, String>> actions = new ArrayList<Map<String, String>>();
        DecisionResponse decision = response == null ? null : response.getDecision();
        if (decision != null && decision.getRecommendations() != null && !decision.getRecommendations().isEmpty()) {
            addChip(actions, "第一家评价如何？", "第一家评价如何？");
            addChip(actions, "第一家有优惠券吗？", "第一家有优惠券吗？");
            addChip(actions, "换个便宜点的", "换个便宜点的，重新推荐");
            return actions;
        }
        AgentConversationResponse conversation = response == null ? null : response.getConversation();
        if (conversation != null && conversation.getFocusedShopName() != null) {
            addChip(actions, "看看优惠券", "这家有优惠券吗？");
            addChip(actions, "查看评价", "这家评价如何？");
        }
        return actions;
    }

    private void addChip(List<Map<String, String>> actions, String label, String query) {
        Map<String, String> chip = new LinkedHashMap<String, String>();
        chip.put("label", label);
        chip.put("query", query);
        actions.add(chip);
    }

    private void sendTextDeltas(SseEmitter emitter, String answer) throws IOException {
        String text = answer == null ? "暂未得到回复。" : answer;
        for (int offset = 0; offset < text.length(); offset += TEXT_DELTA_CHARS) {
            ChatStreamEventData delta = new ChatStreamEventData();
            delta.setDelta(text.substring(offset, Math.min(text.length(), offset + TEXT_DELTA_CHARS)));
            send(emitter, "text_delta", delta);
        }
    }

    private void sendModelTextDelta(SseEmitter emitter, String chunk) {
        try {
            ChatStreamEventData delta = new ChatStreamEventData();
            delta.setDelta(chunk);
            send(emitter, "text_delta", delta);
        } catch (IOException e) {
            throw new IllegalStateException("SSE client disconnected while streaming model output", e);
        }
    }

    private void sendPipelineEvent(SseEmitter emitter, ChatStreamEventData event) {
        try {
            send(emitter, event.getEventName(), event);
        } catch (IOException e) {
            throw new IllegalStateException("SSE client disconnected while sending pipeline event", e);
        }
    }

    private void sendStatus(SseEmitter emitter, String stage, String message) throws IOException {
        ChatStreamEventData status = new ChatStreamEventData();
        status.setStage(stage);
        status.setMessage(message);
        send(emitter, "status", status);
    }

    private void send(SseEmitter emitter, String event, ChatStreamEventData data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data));
    }
}
