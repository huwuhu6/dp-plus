package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.ChatLocationInput;
import com.hmdp.ai.dto.ConversationLocationSlot;
import com.hmdp.ai.dto.ConversationSlots;
import com.hmdp.ai.entity.AiChatSession;
import com.hmdp.ai.mapper.AiChatSessionMapper;
import com.hmdp.utils.UserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;

@Service
public class ConversationStateService {
    private static final Logger log = LoggerFactory.getLogger(ConversationStateService.class);
    private static final int LOCATION_TTL_MINUTES = 30;

    @Resource private AiChatSessionMapper chatSessionMapper;
    @Resource private ObjectMapper objectMapper;

    public AiChatSession getOrCreate(String chatId) {
        AiChatSession state = chatSessionMapper.selectById(chatId);
        if (state == null) {
            state = new AiChatSession();
            state.setChatId(chatId);
            state.setUserId(UserHolder.getUser() == null ? null : UserHolder.getUser().getId());
            state.setVersion(1);
            state.setSlotsJson(writeSlots(new ConversationSlots()));
            chatSessionMapper.insert(state);
        }
        ensureOwner(state);
        return state;
    }

    public ConversationSlots slots(AiChatSession state) {
        try {
            if (state.getSlotsJson() == null || state.getSlotsJson().trim().isEmpty()) return new ConversationSlots();
            ConversationSlots slots = objectMapper.readValue(state.getSlotsJson(), ConversationSlots.class);
            if (slots.getLocation() == null) slots.setLocation(new ConversationLocationSlot());
            return slots;
        } catch (Exception e) {
            throw new IllegalStateException("会话槽位无法读取", e);
        }
    }

    public ConversationLocationSlot usableLocation(AiChatSession state) {
        ConversationSlots stateSlots = slots(state);
        ConversationLocationSlot location = stateSlots.getLocation();
        if (!"AVAILABLE".equals(location.getStatus())) return null;
        if (location.getExpiresAt() != null && !location.getExpiresAt().isAfter(LocalDateTime.now())) {
            location.setStatus("EXPIRED");
            location.setLatitude(null);
            location.setLongitude(null);
            updateSlots(state, stateSlots);
            log.info("[AI][state] event=SLOT_EXPIRED chatId={} slot=location", state.getChatId());
            return null;
        }
        return location;
    }

    public void acceptLocation(AiChatSession state, ChatLocationInput input) {
        if (input == null || input.getLatitude() == null || input.getLongitude() == null) {
            throw new IllegalArgumentException("位置事件必须携带 latitude 和 longitude");
        }
        ConversationSlots slots = slots(state);
        ConversationLocationSlot location = slots.getLocation();
        location.setStatus("AVAILABLE");
        location.setLatitude(input.getLatitude());
        location.setLongitude(input.getLongitude());
        location.setAccuracyMeters(input.getAccuracyMeters());
        location.setSource(input.getSource() == null || input.getSource().trim().isEmpty() ? "CLIENT" : input.getSource());
        location.setCapturedAt(LocalDateTime.now());
        location.setExpiresAt(location.getCapturedAt().plusMinutes(LOCATION_TTL_MINUTES));
        updateSlots(state, slots);
        log.info("[AI][state] event=SLOT_UPDATED chatId={} slot=location status=AVAILABLE source={} latitude={} longitude={} expiresAt={}",
                state.getChatId(), location.getSource(), location.getLatitude(), location.getLongitude(), location.getExpiresAt());
    }

    public void declineLocation(AiChatSession state) {
        ConversationSlots slots = slots(state);
        ConversationLocationSlot location = slots.getLocation();
        location.setStatus("DECLINED");
        location.setLatitude(null);
        location.setLongitude(null);
        location.setAccuracyMeters(null);
        location.setSource("USER_DECLINED");
        location.setCapturedAt(LocalDateTime.now());
        location.setExpiresAt(null);
        updateSlots(state, slots);
        log.info("[AI][state] event=SLOT_UPDATED chatId={} slot=location status=DECLINED", state.getChatId());
    }

    public void activateDecision(AiChatSession state, Long decisionSessionId) {
        if (decisionSessionId == null) return;
        state.setActiveDecisionSessionId(decisionSessionId);
        state.setLastDecisionSessionId(decisionSessionId);
        update(state);
    }

    public void clearActiveDecision(AiChatSession state) {
        if (state.getActiveDecisionSessionId() == null) return;
        state.setActiveDecisionSessionId(null);
        update(state);
    }

    public void rememberLastDecision(AiChatSession state, Long decisionSessionId) {
        if (decisionSessionId == null || decisionSessionId.equals(state.getLastDecisionSessionId())) return;
        state.setLastDecisionSessionId(decisionSessionId);
        update(state);
    }

    private void updateSlots(AiChatSession state, ConversationSlots slots) {
        state.setSlotsJson(writeSlots(slots));
        update(state);
    }

    private void update(AiChatSession state) {
        int version = state.getVersion() == null ? 0 : state.getVersion();
        state.setVersion(version + 1);
        int updated = chatSessionMapper.update(state, new UpdateWrapper<AiChatSession>()
                .eq("chat_id", state.getChatId()).eq("version", version));
        if (updated != 1) throw new IllegalStateException("会话状态已被并发更新，请重试");
    }

    private String writeSlots(ConversationSlots slots) {
        try {
            return objectMapper.writeValueAsString(slots);
        } catch (Exception e) {
            throw new IllegalStateException("会话槽位无法保存", e);
        }
    }

    private void ensureOwner(AiChatSession state) {
        if (state.getUserId() == null) return;
        if (UserHolder.getUser() == null || !state.getUserId().equals(UserHolder.getUser().getId())) {
            throw new SecurityException("无权访问其他用户的聊天会话");
        }
    }
}
