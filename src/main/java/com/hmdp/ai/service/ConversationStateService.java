package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.AgentSessionContext;
import com.hmdp.ai.dto.ChatLocationInput;
import com.hmdp.ai.dto.ConversationLocationSlot;
import com.hmdp.ai.dto.ConversationSlots;
import com.hmdp.ai.dto.ConversationWorkingMemory;
import com.hmdp.ai.dto.CriteriaMergeResult;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.dto.PolicyDecision;
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

    /** An explicit destination is independent from, and takes precedence over, device location. */
    public ConversationLocationSlot usableSearchLocation(AiChatSession state) {
        ConversationWorkingMemory memory = workingMemory(state);
        ConversationLocationSlot target = usable(memory.getSearchLocation());
        return target == null ? usableLocation(state) : target;
    }

    public void acceptLocation(AiChatSession state, ChatLocationInput input) {
        if (input == null || input.getLatitude() == null || input.getLongitude() == null) throw new IllegalArgumentException("Location event requires latitude and longitude");
        ConversationWorkingMemory memory = workingMemory(state);
        ConversationLocationSlot location = memory.getLocation();
        boolean changed = materialLocationChange(location, input);
        location.setStatus("AVAILABLE"); location.setLatitude(input.getLatitude()); location.setLongitude(input.getLongitude());
        location.setAccuracyMeters(input.getAccuracyMeters()); location.setSource(hasText(input.getSource()) ? input.getSource() : "CLIENT");
        location.setCapturedAt(LocalDateTime.now()); location.setExpiresAt(location.getCapturedAt().plusMinutes(LOCATION_TTL_MINUTES));
        if (changed) {
            int previousCandidateCount = invalidateCandidatePool(memory);
            log.info("[AI][state] event=CASCADE_INVALIDATED chatId={} reason=LOCATION_CHANGED previousCandidates={}",
                    state.getChatId(), previousCandidateCount);
        }
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
        return acceptPendingSearchLocation(state, index);
    }

    public ResolvedLocationCandidate acceptPendingSearchLocation(AiChatSession state, int index) {
        ConversationWorkingMemory memory = workingMemory(state);
        List<ResolvedLocationCandidate> candidates = memory.getPendingLocationCandidates();
        if (candidates == null || index < 0 || index >= candidates.size()) throw new IllegalArgumentException("Location candidate has expired");
        ResolvedLocationCandidate candidate = candidates.get(index);
        ConversationLocationSlot target = memory.getSearchLocation();
        boolean changed = materialLocationChange(target, candidate.getLatitude(), candidate.getLongitude());
        target.setStatus("AVAILABLE"); target.setLatitude(candidate.getLatitude()); target.setLongitude(candidate.getLongitude());
        target.setProvince(candidate.getProvince()); target.setCity(candidate.getCity()); target.setDistrict(candidate.getDistrict());
        target.setSource(candidate.getSource()); target.setCapturedAt(LocalDateTime.now()); target.setExpiresAt(target.getCapturedAt().plusMinutes(LOCATION_TTL_MINUTES));
        if (changed) {
            int previousCandidateCount = invalidateCandidatePool(memory);
            log.info("[AI][state] event=CASCADE_INVALIDATED chatId={} reason=SEARCH_LOCATION_CHANGED previousCandidates={}",
                    state.getChatId(), previousCandidateCount);
        }
        memory.setPendingLocationCandidates(new ArrayList<ResolvedLocationCandidate>());
        updateWorkingMemory(state, memory);
        log.info("[AI][state] event=SEARCH_LOCATION_CONFIRMED chatId={} index={} label={} latitude={} longitude={}", state.getChatId(), index, candidate.getLabel(), candidate.getLatitude(), candidate.getLongitude());
        return candidate;
    }

    public void recordPolicy(AiChatSession state, PolicyDecision decision) {
        if (decision == null) return;
        ConversationWorkingMemory memory = workingMemory(state);
        memory.setLastPolicyAction(decision.getAction());
        memory.setLastPolicyReason(decision.getReason());
        updateWorkingMemory(state, memory);
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

    /** Applies the deterministic criteria delta before a new decision can reuse stale candidates. */
    public void reduceCriteria(AiChatSession state, CriteriaMergeResult reduction) {
        if (reduction == null || reduction.getConstraints() == null) return;
        ConversationWorkingMemory memory = workingMemory(state);
        memory.setActiveCriteria(reduction.getConstraints());
        if (changesCandidateUniverse(reduction)) {
            boolean hadFocusedShop = memory.getFocusedShopId() != null;
            int previousCandidateCount = invalidateCandidatePool(memory);
            if (previousCandidateCount > 0) reduction.getInvalidated().add("candidatePool=" + previousCandidateCount);
            if (hadFocusedShop) reduction.getInvalidated().add("focusedShop");
        }
        updateWorkingMemory(state, memory);
        log.info("[AI][state] event=STATE_REDUCED chatId={} inherited={} replaced={} appended={} cleared={} invalidated={}",
                state.getChatId(), reduction.getInherited(), reduction.getReplaced(), reduction.getAppended(),
                reduction.getCleared(), reduction.getInvalidated());
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

    /** Commits the chat-path tool reducer output back into the single conversation memory. */
    public void applyAgentContext(AiChatSession state, Long sessionId, AgentSessionContext context) {
        if (context == null) return;
        ConversationWorkingMemory memory = workingMemory(state);
        memory.setSourceDecisionSessionId(sessionId);
        memory.setCandidatePool(new ArrayList<DecisionRecommendation>(context.getShownShops() == null
                ? new ArrayList<DecisionRecommendation>() : context.getShownShops()));
        memory.setFocusedShopId(context.getFocusedShopId());
        memory.setFocusedShopName(context.getFocusedShopName());
        memory.setDialogPhase("RECOMMENDING");
        updateWorkingMemory(state, memory);
        log.info("[AI][state] event=AGENT_CONTEXT_REDUCED chatId={} sessionId={} candidates={} focusedShopId={}",
                state.getChatId(), sessionId, memory.getCandidatePool().size(), memory.getFocusedShopId());
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
    private void normalize(ConversationWorkingMemory memory) { if (memory.getLocation() == null) memory.setLocation(new ConversationLocationSlot()); if (memory.getSearchLocation() == null) memory.setSearchLocation(new ConversationLocationSlot()); if (memory.getPendingLocationCandidates() == null) memory.setPendingLocationCandidates(new ArrayList<ResolvedLocationCandidate>()); if (memory.getActiveCriteria() == null) memory.setActiveCriteria(new DecisionConstraints()); if (memory.getCandidatePool() == null) memory.setCandidatePool(new ArrayList<DecisionRecommendation>()); if (!hasText(memory.getDialogPhase())) memory.setDialogPhase("IDLE"); if (!hasText(memory.getLastPolicyAction())) memory.setLastPolicyAction("NONE"); }
    private boolean changesCandidateUniverse(CriteriaMergeResult reduction) {
        // A candidate pool is only valid for the exact retrieval domain that produced it.
        // Be deliberately conservative: preserving a stale reference is worse than asking
        // the next turn to search again.
        return hasSearchDomainMutation(reduction.getReplaced())
                || hasSearchDomainMutation(reduction.getAppended())
                || reduction.getCleared().stream().anyMatch(this::isSearchDomainField);
    }

    private boolean hasSearchDomainMutation(List<String> changes) {
        if (changes == null) return false;
        for (String item : changes) {
            String field = item == null ? "" : item.split(":", 2)[0];
            if (isSearchDomainField(field)) return true;
        }
        return false;
    }

    private boolean isSearchDomainField(String field) {
        return "cuisine".equals(field) || "budgetPerPerson".equals(field) || "radiusKm".equals(field)
                || "nearby".equals(field) || "arrivalTime".equals(field) || "occasion".equals(field)
                || "quiet".equals(field) || "avoidQueue".equals(field) || "hardConstraints".equals(field)
                || "softPreferences".equals(field);
    }
    private int invalidateCandidatePool(ConversationWorkingMemory memory) {
        int previousCandidateCount = memory.getCandidatePool().size();
        memory.setCandidatePool(new ArrayList<DecisionRecommendation>());
        memory.setFocusedShopId(null);
        memory.setFocusedShopName(null);
        return previousCandidateCount;
    }
    private boolean materialLocationChange(ConversationLocationSlot current, ChatLocationInput next) {
        if (current == null || current.getLatitude() == null || current.getLongitude() == null) return false;
        double latitudeDelta = current.getLatitude() - next.getLatitude();
        double longitudeDelta = current.getLongitude() - next.getLongitude();
        return Math.sqrt(latitudeDelta * latitudeDelta + longitudeDelta * longitudeDelta) > 0.005D;
    }
    private boolean materialLocationChange(ConversationLocationSlot current, Double latitude, Double longitude) {
        if (current == null || current.getLatitude() == null || current.getLongitude() == null) return false;
        if (latitude == null || longitude == null) return true;
        double latitudeDelta = current.getLatitude() - latitude;
        double longitudeDelta = current.getLongitude() - longitude;
        return Math.sqrt(latitudeDelta * latitudeDelta + longitudeDelta * longitudeDelta) > 0.005D;
    }
    private ConversationLocationSlot usable(ConversationLocationSlot location) {
        if (location == null || !"AVAILABLE".equals(location.getStatus())) return null;
        if (location.getExpiresAt() != null && !location.getExpiresAt().isAfter(LocalDateTime.now())) return null;
        return location.getLatitude() == null || location.getLongitude() == null ? null : location;
    }
    private void clearLocation(ConversationLocationSlot location, String status) { location.setStatus(status); location.setLatitude(null); location.setLongitude(null); location.setProvince(null); location.setCity(null); location.setDistrict(null); location.setAccuracyMeters(null); location.setExpiresAt(null); }
    private void ensureOwner(AiChatSession state) { if (state.getUserId() == null) return; if (UserHolder.getUser() == null || !state.getUserId().equals(UserHolder.getUser().getId())) throw new SecurityException("No permission to access this chat session"); }
    private boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }
}
