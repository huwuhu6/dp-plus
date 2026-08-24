package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.AgentSessionContext;
import com.hmdp.ai.dto.ChatLocationInput;
import com.hmdp.ai.dto.ConversationLocationSlot;
import com.hmdp.ai.dto.ConversationSlots;
import com.hmdp.ai.dto.ConversationWorkingMemory;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.dto.ResolvedLocationCandidate;
import com.hmdp.ai.entity.AiChatSession;
import com.hmdp.ai.mapper.AiChatSessionMapper;
import com.hmdp.utils.UserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
            ConversationWorkingMemory memory = new ConversationWorkingMemory();
            state.setSlotsJson(writeLegacySlots(memory));
            state.setWorkingMemoryJson(writeWorkingMemory(memory));
            chatSessionMapper.insert(state);
        }
        ensureOwner(state);
        return state;
    }

    public ConversationSlots slots(AiChatSession state) {
        ConversationWorkingMemory memory = workingMemory(state);
        ConversationSlots slots = new ConversationSlots();
        slots.setLocation(memory.getLocation());
        slots.setPendingLocationCandidates(memory.getPendingLocationCandidates());
        return slots;
    }

    public ConversationWorkingMemory workingMemory(AiChatSession state) {
        try {
            ConversationWorkingMemory memory;
            if (hasText(state.getWorkingMemoryJson())) {
                memory = objectMapper.readValue(state.getWorkingMemoryJson(), ConversationWorkingMemory.class);
            } else {
                memory = new ConversationWorkingMemory();
                if (hasText(state.getSlotsJson())) {
                    ConversationSlots legacy = objectMapper.readValue(state.getSlotsJson(), ConversationSlots.class);
                    memory.setLocation(legacy.getLocation());
                    memory.setPendingLocationCandidates(legacy.getPendingLocationCandidates());
                }
            }
            normalize(memory);
            return memory;
        } catch (Exception e) {
            throw new IllegalStateException("Conversation working memory cannot be read", e);
        }
    }

    public ConversationLocationSlot usableLocation(AiChatSession state) {
        ConversationWorkingMemory memory = workingMemory(state);
        ConversationLocationSlot location = memory.getLocation();
        if (!"AVAILABLE".equals(location.getStatus())) return null;
        if (location.getExpiresAt() != null && !location.getExpiresAt().isAfter(LocalDateTime.now())) {
            clearLocation(location, "EXPIRED");
            updateWorkingMemory(state, memory);
            log.info("[AI][state] event=SLOT_EXPIRED chatId={} slot=location", state.getChatId());
            return null;
        }
        return location;
    }

    public void acceptLocation(AiChatSession state, ChatLocationInput input) {
        if (input == null || input.getLatitude() == null || input.getLongitude() == null) throw new IllegalArgumentException("Location event requires latitude and longitude");
        ConversationWorkingMemory memory = workingMemory(state);
        ConversationLocationSlot location = memory.getLocation();
        location.setStatus("AVAILABLE"); location.setLatitude(input.getLatitude()); location.setLongitude(input.getLongitude());
        location.setAccuracyMeters(input.getAccuracyMeters()); location.setSource(hasText(input.getSource()) ? input.getSource() : "CLIENT");
        location.setCapturedAt(LocalDateTime.now()); location.setExpiresAt(location.getCapturedAt().plusMinutes(LOCATION_TTL_MINUTES));
        updateWorkingMemory(state, memory);
        log.info("[AI][state] event=SLOT_UPDATED chatId={} slot=location status=AVAILABLE source={} latitude={} longitude={} expiresAt={}", state.getChatId(), location.getSource(), location.getLatitude(), location.getLongitude(), location.getExpiresAt());
    }

    public void rememberLocationCandidates(AiChatSession state, List<ResolvedLocationCandidate> candidates) {
        ConversationWorkingMemory memory = workingMemory(state);
        memory.setPendingLocationCandidates(candidates == null ? new ArrayList<ResolvedLocationCandidate>() : new ArrayList<ResolvedLocationCandidate>(candidates));
        updateWorkingMemory(state, memory);
        log.info("[AI][state] event=LOCATION_CANDIDATES_SAVED chatId={} candidates={}", state.getChatId(), memory.getPendingLocationCandidates().size());
    }

    public ResolvedLocationCandidate acceptPendingLocation(AiChatSession state, int index) {
        ConversationWorkingMemory memory = workingMemory(state);
        List<ResolvedLocationCandidate> candidates = memory.getPendingLocationCandidates();
        if (candidates == null || index < 0 || index >= candidates.size()) throw new IllegalArgumentException("Location candidate has expired");
        ResolvedLocationCandidate candidate = candidates.get(index);
        ChatLocationInput input = new ChatLocationInput(); input.setLatitude(candidate.getLatitude()); input.setLongitude(candidate.getLongitude()); input.setSource(candidate.getSource());
        acceptLocation(state, input);
        memory = workingMemory(state);
        memory.getLocation().setProvince(candidate.getProvince()); memory.getLocation().setCity(candidate.getCity()); memory.getLocation().setDistrict(candidate.getDistrict());
        memory.setPendingLocationCandidates(new ArrayList<ResolvedLocationCandidate>());
        updateWorkingMemory(state, memory);
        log.info("[AI][state] event=LOCATION_CANDIDATE_CONFIRMED chatId={} index={} label={} latitude={} longitude={}", state.getChatId(), index, candidate.getLabel(), candidate.getLatitude(), candidate.getLongitude());
        return candidate;
    }

    public void declineLocation(AiChatSession state) {
        ConversationWorkingMemory memory = workingMemory(state);
        clearLocation(memory.getLocation(), "DECLINED");
        memory.getLocation().setSource("USER_DECLINED"); memory.getLocation().setCapturedAt(LocalDateTime.now());
        updateWorkingMemory(state, memory);
        log.info("[AI][state] event=SLOT_UPDATED chatId={} slot=location status=DECLINED", state.getChatId());
    }

    public void activateDecision(AiChatSession state, Long decisionSessionId) {
        if (decisionSessionId == null) return;
        state.setActiveDecisionSessionId(decisionSessionId); state.setLastDecisionSessionId(decisionSessionId); update(state);
    }

    public void snapshotDecision(AiChatSession state, DecisionResponse decision) {
        if (decision == null) return;
        ConversationWorkingMemory memory = workingMemory(state);
        memory.setSourceDecisionSessionId(decision.getSessionId()); memory.setDialogPhase(hasText(decision.getStatus()) ? decision.getStatus() : "IDLE");
        if (decision.getConstraints() != null) memory.setActiveCriteria(decision.getConstraints());
        List<DecisionRecommendation> recommendations = decision.getRecommendations() == null
                ? new ArrayList<DecisionRecommendation>() : decision.getRecommendations();
        memory.setCandidatePool(new ArrayList<DecisionRecommendation>(recommendations));
        if (!recommendations.isEmpty()) {
            DecisionRecommendation first = recommendations.get(0);
            memory.setFocusedShopId(first.getShopId()); memory.setFocusedShopName(first.getShopName());
        } else {
            // A new task with no candidates must not expose the previous task's shops to rewriting or tools.
            memory.setFocusedShopId(null); memory.setFocusedShopName(null);
        }
        updateWorkingMemory(state, memory);
        log.info("[AI][state] event=WORKING_MEMORY_SNAPSHOT chatId={} sessionId={} phase={} candidates={} focusedShopId={}", state.getChatId(), decision.getSessionId(), memory.getDialogPhase(), memory.getCandidatePool().size(), memory.getFocusedShopId());
    }

    public void snapshotFollowUp(AiChatSession state, Long sessionId, Long focusedShopId, String focusedShopName) {
        ConversationWorkingMemory memory = workingMemory(state);
        memory.setSourceDecisionSessionId(sessionId);
        if (focusedShopId != null) memory.setFocusedShopId(focusedShopId);
        if (hasText(focusedShopName)) memory.setFocusedShopName(focusedShopName);
        updateWorkingMemory(state, memory);
        log.info("[AI][state] event=WORKING_MEMORY_FOCUS_UPDATED chatId={} sessionId={} focusedShopId={}", state.getChatId(), sessionId, memory.getFocusedShopId());
    }

    public AgentSessionContext agentContext(AiChatSession state) {
        ConversationWorkingMemory memory = workingMemory(state);
        AgentSessionContext context = new AgentSessionContext();
        context.setFocusedShopId(memory.getFocusedShopId()); context.setFocusedShopName(memory.getFocusedShopName());
        context.setShownShops(new ArrayList<DecisionRecommendation>(memory.getCandidatePool()));
        List<Long> ids = new ArrayList<Long>(); for (DecisionRecommendation item : memory.getCandidatePool()) ids.add(item.getShopId());
        context.setShownShopIds(ids); context.setDecisionConstraints(memory.getActiveCriteria());
        return context;
    }

    public void clearActiveDecision(AiChatSession state) { if (state.getActiveDecisionSessionId() != null) { state.setActiveDecisionSessionId(null); update(state); } }
    public void rememberLastDecision(AiChatSession state, Long decisionSessionId) { if (decisionSessionId != null && !decisionSessionId.equals(state.getLastDecisionSessionId())) { state.setLastDecisionSessionId(decisionSessionId); update(state); } }

    private void updateWorkingMemory(AiChatSession state, ConversationWorkingMemory memory) { normalize(memory); state.setWorkingMemoryJson(writeWorkingMemory(memory)); state.setSlotsJson(writeLegacySlots(memory)); update(state); }
    private void update(AiChatSession state) { int version = state.getVersion() == null ? 0 : state.getVersion(); state.setVersion(version + 1); int updated = chatSessionMapper.update(state, new UpdateWrapper<AiChatSession>().eq("chat_id", state.getChatId()).eq("version", version)); if (updated != 1) throw new IllegalStateException("Conversation state changed concurrently"); }
    private String writeLegacySlots(ConversationWorkingMemory memory) { ConversationSlots slots = new ConversationSlots(); slots.setLocation(memory.getLocation()); slots.setPendingLocationCandidates(memory.getPendingLocationCandidates()); try { return objectMapper.writeValueAsString(slots); } catch (Exception e) { throw new IllegalStateException("Conversation location slots cannot be saved", e); } }
    private String writeWorkingMemory(ConversationWorkingMemory memory) { try { return objectMapper.writeValueAsString(memory); } catch (Exception e) { throw new IllegalStateException("Conversation working memory cannot be saved", e); } }
    private void normalize(ConversationWorkingMemory memory) { if (memory.getLocation() == null) memory.setLocation(new ConversationLocationSlot()); if (memory.getPendingLocationCandidates() == null) memory.setPendingLocationCandidates(new ArrayList<ResolvedLocationCandidate>()); if (memory.getActiveCriteria() == null) memory.setActiveCriteria(new DecisionConstraints()); if (memory.getCandidatePool() == null) memory.setCandidatePool(new ArrayList<DecisionRecommendation>()); if (!hasText(memory.getDialogPhase())) memory.setDialogPhase("IDLE"); }
    private void clearLocation(ConversationLocationSlot location, String status) { location.setStatus(status); location.setLatitude(null); location.setLongitude(null); location.setProvince(null); location.setCity(null); location.setDistrict(null); location.setAccuracyMeters(null); location.setExpiresAt(null); }
    private void ensureOwner(AiChatSession state) { if (state.getUserId() == null) return; if (UserHolder.getUser() == null || !state.getUserId().equals(UserHolder.getUser().getId())) throw new SecurityException("No permission to access this chat session"); }
    private boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }
}
