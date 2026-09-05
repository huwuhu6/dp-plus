package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.AgentSessionContext;
import com.hmdp.ai.dto.ChatLocationInput;
import com.hmdp.ai.dto.ConversationLocationSlot;
import com.hmdp.ai.dto.ConversationSlots;
import com.hmdp.ai.dto.ConversationWorkingMemory;
import com.hmdp.ai.dto.CriteriaMergeResult;
import com.hmdp.ai.dto.ConstraintSource;
import com.hmdp.ai.dto.DecisionTaskState;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.dto.PolicyDecision;
import com.hmdp.ai.dto.ResolvedLocationCandidate;
import com.hmdp.ai.dto.RecommendationBatch;
import com.hmdp.ai.dto.RecommendationCandidateRef;
import com.hmdp.ai.entity.AiChatSession;
import com.hmdp.ai.entity.AiWorkingMemory;
import com.hmdp.ai.mapper.AiChatSessionMapper;
import com.hmdp.ai.mapper.AiWorkingMemoryMapper;
import com.hmdp.ai.runtime.ConversationEventType;
import com.hmdp.utils.UserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ConversationStateService {
    private static final Logger log = LoggerFactory.getLogger(ConversationStateService.class);
    private static final int LOCATION_TTL_MINUTES = 30;

    @Resource private AiChatSessionMapper chatSessionMapper;
    @Resource private AiWorkingMemoryMapper workingMemoryMapper;
    @Resource private WorkingMemoryVersionService workingMemoryVersionService;
    @Resource private ConversationEventService conversationEventService;
    @Resource private ObjectMapper objectMapper;

    public AiChatSession getOrCreate(String chatId) {
        AiWorkingMemory latest = workingMemoryVersionService.latest(chatId);
        AiChatSession state = new AiChatSession();
        state.setChatId(chatId);
        if (latest == null) {
            // The legacy row is read once for in-place database upgrades, never updated again.
            AiChatSession legacy = chatSessionMapper.selectById(chatId);
            if (legacy != null) {
                state = legacy;
                ConversationWorkingMemory memory = workingMemory(legacy);
                memory.setActiveDecisionSessionId(legacy.getActiveDecisionSessionId());
                memory.setLastDecisionSessionId(legacy.getLastDecisionSessionId());
                persistMemory(state, memory, ConversationEventType.STATE_REDUCED, "LEGACY_MEMORY_IMPORTED");
            } else {
                state.setUserId(UserHolder.getUser() == null ? null : UserHolder.getUser().getId());
                state.setVersion(0);
                persistMemory(state, new ConversationWorkingMemory(), ConversationEventType.STATE_REDUCED, "INITIAL_MEMORY_CREATED");
            }
        } else {
            state.setUserId(latest.getUserId());
            state.setVersion(latest.getVersion());
            state.setWorkingMemoryJson(latest.getMemoryJson());
            ConversationWorkingMemory memory = workingMemory(state);
            state.setActiveDecisionSessionId(memory.getActiveDecisionSessionId());
            state.setLastDecisionSessionId(memory.getLastDecisionSessionId());
        }
        /*
         * ai_chat_session is retained as a legacy read-only migration source. The
         * versioned memory table is now the single durable state source.
         */
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

    public DecisionTaskState activeTask(ConversationWorkingMemory memory) {
        return memory.activeTask();
    }

    public DecisionTaskState ensureActiveTask(ConversationWorkingMemory memory) {
        return memory.ensureActiveTask();
    }

    public DecisionConstraints activeCriteria(ConversationWorkingMemory memory) {
        DecisionTaskState task = activeTask(memory);
        return task == null ? null : task.getCriteria();
    }

    public ConversationLocationSlot searchLocation(ConversationWorkingMemory memory) {
        DecisionTaskState task = activeTask(memory);
        return task == null ? null : task.getSearchLocation();
    }

    public List<DecisionRecommendation> latestCandidatePool(ConversationWorkingMemory memory) {
        DecisionTaskState task = activeTask(memory);
        if (task == null || task.getRecommendationBatches() == null || task.getRecommendationBatches().isEmpty()) return new ArrayList<DecisionRecommendation>();
        return recommendations(task.getRecommendationBatches().get(task.getRecommendationBatches().size() - 1));
    }

    public List<Long> shownShopIds(ConversationWorkingMemory memory) {
        List<Long> shown = new ArrayList<Long>();
        DecisionTaskState task = activeTask(memory);
        if (task == null || task.getRecommendationBatches() == null) return shown;
        for (RecommendationBatch batch : task.getRecommendationBatches()) for (DecisionRecommendation item : recommendations(batch)) {
            if (item.getShopId() != null && !shown.contains(item.getShopId())) shown.add(item.getShopId());
        }
        return shown;
    }

    public Long latestSourceDecisionSessionId(ConversationWorkingMemory memory) {
        DecisionTaskState task = activeTask(memory);
        if (task == null || task.getRecommendationBatches() == null || task.getRecommendationBatches().isEmpty()) return null;
        return task.getRecommendationBatches().get(task.getRecommendationBatches().size() - 1).getDecisionSessionId();
    }

    private boolean activateTaskForDecisionSession(ConversationWorkingMemory memory, Long sessionId) {
        if (sessionId == null || memory.getTasks() == null) return false;
        for (DecisionTaskState task : memory.getTasks()) {
            if (task.getRecommendationBatches() == null) continue;
            for (RecommendationBatch batch : task.getRecommendationBatches()) {
                if (sessionId.equals(batch.getDecisionSessionId())) {
                    if (!task.getTaskId().equals(memory.getActiveTaskId())) {
                        memory.setActiveTaskId(task.getTaskId());
                        memory.setFocusedShopId(null); memory.setFocusedShopName(null);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    public DecisionTaskState createTask(ConversationWorkingMemory memory, String title) {
        DecisionTaskState task = new DecisionTaskState();
        task.setTaskId(UUID.randomUUID().toString());
        task.setTitle(title == null || title.isBlank() ? "新的推荐" : title);
        memory.getTasks().add(task); memory.setActiveTaskId(task.getTaskId());
        memory.setFocusedShopId(null); memory.setFocusedShopName(null);
        return task;
    }

    /**
     * Selects the task that must receive a decision delta before that delta is merged.
     * A task identity is deliberately limited to explicit destination and cuisine: budgets
     * and preferences refine an existing demand rather than creating a sibling task.
     */
    public TaskTransition transitionTask(ConversationWorkingMemory memory, DecisionConstraints extracted, String message) {
        DecisionTaskState before = activeTask(memory);
        if (before == null) {
            ensureActiveTask(memory);
            return new TaskTransition("UPDATE", "INITIAL_TASK", null, memory.getActiveTaskId());
        }
        if (activateHistoricalTask(memory, message)) {
            return new TaskTransition("SWITCH", "EXPLICIT_HISTORY", before.getTaskId(), memory.getActiveTaskId());
        }

        DemandSignature resolved = DemandSignature.resolve(before.getCriteria(), extracted);
        DecisionTaskState matchingTask = uniqueHistoricalMatch(memory, resolved, before.getTaskId());
        if (matchingTask != null) {
            memory.setActiveTaskId(matchingTask.getTaskId());
            memory.setFocusedShopId(null); memory.setFocusedShopName(null);
            return new TaskTransition("SWITCH", "UNIQUE_SIGNATURE", before.getTaskId(), matchingTask.getTaskId());
        }
        if (isIndependentDemand(before.getCriteria(), resolved)) {
            DecisionTaskState created = createTask(memory, resolved.title());
            return new TaskTransition("CREATE", "DESTINATION_AND_CUISINE_REPLACED", before.getTaskId(), created.getTaskId());
        }
        return new TaskTransition("UPDATE", "REFINEMENT_OR_PARTIAL_REPLACEMENT", before.getTaskId(), before.getTaskId());
    }

    private DecisionTaskState uniqueHistoricalMatch(ConversationWorkingMemory memory, DemandSignature resolved, String activeTaskId) {
        DecisionTaskState match = null;
        for (DecisionTaskState task : memory.getTasks()) {
            if (task == null || task.getTaskId().equals(activeTaskId) || !resolved.matches(DemandSignature.of(task.getCriteria()))) continue;
            if (match != null) return null;
            match = task;
        }
        return match;
    }

    private boolean isIndependentDemand(DecisionConstraints active, DemandSignature resolved) {
        DemandSignature current = DemandSignature.of(active);
        return current.hasCityAndCuisine() && resolved.hasCityAndCuisine()
                && !current.city.equals(resolved.city) && !current.cuisine.equals(resolved.cuisine);
    }

    public record TaskTransition(String action, String reason, String activeTaskIdBefore, String activeTaskIdAfter) { }

    private record DemandSignature(String city, String area, String cuisine) {
        private static DemandSignature of(DecisionConstraints criteria) {
            return new DemandSignature(normalizeCity(criteria == null ? null : criteria.getTargetCity()),
                    normalize(criteria == null ? null : criteria.getTargetArea()),
                    normalize(criteria == null ? null : criteria.getCuisine()));
        }
        private static DemandSignature resolve(DecisionConstraints previous, DecisionConstraints delta) {
            DemandSignature base = of(previous);
            return new DemandSignature(hasText(delta == null ? null : delta.getTargetCity()) ? normalizeCity(delta.getTargetCity()) : base.city,
                    hasText(delta == null ? null : delta.getTargetArea()) ? normalize(delta.getTargetArea()) : base.area,
                    hasText(delta == null ? null : delta.getCuisine()) ? normalize(delta.getCuisine()) : base.cuisine);
        }
        private boolean hasCityAndCuisine() { return !city.isEmpty() && !cuisine.isEmpty(); }
        private boolean matches(DemandSignature other) {
            return hasCityAndCuisine() && other.hasCityAndCuisine()
                    && city.equals(other.city) && area.equals(other.area) && cuisine.equals(other.cuisine);
        }
        private String title() { return city + (area.isEmpty() ? "" : area) + cuisine; }
        private static String normalizeCity(String value) {
            String normalized = normalize(value);
            return normalized.endsWith("市") ? normalized.substring(0, normalized.length() - 1) : normalized;
        }
        private static String normalize(String value) { return value == null ? "" : value.trim(); }
        private static boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }
    }

    public boolean activateHistoricalTask(ConversationWorkingMemory memory, String message) {
        if (memory.getTasks().size() < 2 || message == null) return false;
        String text = message.replaceAll("\\s+", "");
        DecisionTaskState candidate = null;
        if (text.contains("最开始")) candidate = memory.getTasks().get(0);
        else if (text.contains("之前") || text.contains("上一个")) {
            for (int i = memory.getTasks().size() - 1; i >= 0; i--) if (!memory.getTasks().get(i).getTaskId().equals(memory.getActiveTaskId())) { candidate = memory.getTasks().get(i); break; }
        }
        if (candidate == null) return false;
        memory.setActiveTaskId(candidate.getTaskId());
        memory.setFocusedShopId(null); memory.setFocusedShopName(null);
        return true;
    }

    /** Parses and normalizes a historical snapshot without making it current state. */
    public ConversationWorkingMemory historicalWorkingMemory(String memoryJson) {
        AiChatSession snapshot = new AiChatSession();
        snapshot.setWorkingMemoryJson(memoryJson);
        return workingMemory(snapshot);
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
        ConversationLocationSlot target = usable(searchLocation(memory));
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
        if (candidates == null || index < 0 || index >= candidates.size()) {
            throw new IllegalArgumentException("地点候选不存在或已失效，请重新解析地点后再确认");
        }
        ResolvedLocationCandidate candidate = candidates.get(index);
        ConversationLocationSlot target = ensureActiveTask(memory).getSearchLocation();
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
        // Policy is runtime diagnostics, not durable business state. Do not create
        // a meaningless Working Memory version merely to retain a log field.
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
        ConversationWorkingMemory memory = workingMemory(state);
        memory.setActiveDecisionSessionId(decisionSessionId); memory.setLastDecisionSessionId(decisionSessionId);
        updateWorkingMemory(state, memory);
    }

    /** Applies the deterministic criteria delta before a new decision can reuse stale candidates. */
    public void reduceCriteria(AiChatSession state, CriteriaMergeResult reduction) {
        if (reduction == null || reduction.getConstraints() == null) return;
        reduceCriteria(state, workingMemory(state), reduction);
    }

    /** Persists a reduction against the pipeline snapshot so a task transition is not lost between nodes. */
    public void reduceCriteria(AiChatSession state, ConversationWorkingMemory memory, CriteriaMergeResult reduction) {
        if (reduction == null || reduction.getConstraints() == null) return;
        if (memory == null) memory = workingMemory(state);
        DecisionTaskState task = ensureActiveTask(memory);
        normalizeNearbyDefault(task, reduction);
        task.setCriteria(reduction.getConstraints());
        markConstraintSources(task, reduction);
        synchronizeNamedSearchLocation(state, memory, reduction);
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
        DecisionTaskState task = ensureActiveTask(memory);
        memory.setDialogPhase(hasText(decision.getStatus()) ? decision.getStatus() : "IDLE");
        // Execution constraints are an immutable per-session input snapshot. The task reducer is
        // the only normal writer of activeTask.criteria; execution must never overwrite it.
        List<DecisionRecommendation> recommendations = decision.getRecommendations() == null
                ? new ArrayList<DecisionRecommendation>() : decision.getRecommendations();
        if (!sameBatch(task, decision.getSessionId(), recommendations)) appendRecommendationBatch(task, decision.getSessionId(), recommendations);
        if (!recommendations.isEmpty()) {
            DecisionRecommendation first = recommendations.get(0);
            memory.setFocusedShopId(first.getShopId()); memory.setFocusedShopName(first.getShopName());
        } else {
            // A new task with no candidates must not expose the previous task's shops to rewriting or tools.
            memory.setFocusedShopId(null); memory.setFocusedShopName(null);
        }
        updateWorkingMemory(state, memory);
        log.info("[AI][state] event=WORKING_MEMORY_SNAPSHOT chatId={} sessionId={} phase={} candidates={} focusedShopId={}", state.getChatId(), decision.getSessionId(), memory.getDialogPhase(), recommendations.size(), memory.getFocusedShopId());
    }

    public void snapshotFollowUp(AiChatSession state, Long sessionId, Long focusedShopId, String focusedShopName) {
        ConversationWorkingMemory memory = workingMemory(state);
        if (focusedShopId != null) memory.setFocusedShopId(focusedShopId);
        if (hasText(focusedShopName)) memory.setFocusedShopName(focusedShopName);
        updateWorkingMemory(state, memory);
        log.info("[AI][state] event=WORKING_MEMORY_FOCUS_UPDATED chatId={} sessionId={} focusedShopId={}", state.getChatId(), sessionId, memory.getFocusedShopId());
    }

    /** Commits the chat-path tool reducer output back into the single conversation memory. */
    public void applyAgentContext(AiChatSession state, Long sessionId, AgentSessionContext context) {
        if (context == null) return;
        AiWorkingMemory latest = workingMemoryVersionService.latest(state.getChatId());
        int latestVersion = latest == null || latest.getVersion() == null
                ? (state.getVersion() == null ? 0 : state.getVersion()) : latest.getVersion();
        if (context.getBaseWorkingMemoryVersion() == null
                || !context.getBaseWorkingMemoryVersion().equals(latestVersion)) {
            Map<String, Object> metadata = new LinkedHashMap<String, Object>();
            metadata.put("baseWorkingMemoryVersion", context.getBaseWorkingMemoryVersion());
            metadata.put("actualWorkingMemoryVersion", latestVersion);
            metadata.put("sessionId", sessionId);
            if (conversationEventService != null) {
                conversationEventService.recordBestEffort(ConversationEventType.STALE_RUNTIME_RESULT,
                        com.hmdp.ai.runtime.ConversationEventStatus.SKIPPED, null, null, null, metadata);
            }
            log.warn("[AI][state] event=STALE_RUNTIME_RESULT chatId={} sessionId={} baseVersion={} actualVersion={}",
                    state.getChatId(), sessionId, context.getBaseWorkingMemoryVersion(), latestVersion);
            throw new StaleRuntimeResultException(state.getChatId(), context.getBaseWorkingMemoryVersion() == null ? -1
                    : context.getBaseWorkingMemoryVersion(), latestVersion, sessionId);
        }
        ConversationWorkingMemory memory = workingMemory(state);
        List<DecisionRecommendation> candidates = context.getCandidatePoolSnapshot() == null
                ? new ArrayList<DecisionRecommendation>() : context.getCandidatePoolSnapshot();
        DecisionTaskState task = ensureActiveTask(memory);
        if (!sameCandidateIds(latestCandidatePool(memory), candidates)) appendRecommendationBatch(task, sessionId, candidates);
        memory.setFocusedShopId(context.getFocusedShopId());
        memory.setFocusedShopName(context.getFocusedShopName());
        memory.setDialogPhase("RECOMMENDING");
        updateWorkingMemory(state, memory);
        log.info("[AI][state] event=AGENT_CONTEXT_REDUCED chatId={} sessionId={} candidates={} focusedShopId={}",
                state.getChatId(), sessionId, candidates.size(), memory.getFocusedShopId());
    }

    public AgentSessionContext agentContext(AiChatSession state) {
        ConversationWorkingMemory memory = workingMemory(state);
        AgentSessionContext context = new AgentSessionContext();
        context.setBaseWorkingMemoryVersion(state.getVersion());
        context.setFocusedShopId(memory.getFocusedShopId()); context.setFocusedShopName(memory.getFocusedShopName());
        context.setCandidatePoolSnapshot(latestCandidatePool(memory));
        context.setShownShopIdsSnapshot(shownShopIds(memory));
        context.setDecisionConstraints(activeCriteria(memory));
        return context;
    }

    private void markConstraintSources(DecisionTaskState task, CriteriaMergeResult reduction) {
        for (String change : reduction.getReplaced()) {
            String field = change.split(":", 2)[0];
            if (("budgetPerPerson".equals(field) || "radiusKm".equals(field) || "nearby".equals(field))
                    && task.getConstraintSources().get(field) != ConstraintSource.SYSTEM_DEFAULT) task.getConstraintSources().put(field, ConstraintSource.USER_EXPLICIT);
        }
        for (String appended : reduction.getAppended()) {
            if (appended.startsWith("relativeBudget") || appended.startsWith("relativeDistance")) {
                task.getConstraintSources().put(appended.startsWith("relativeBudget") ? "budgetPerPerson" : "radiusKm", ConstraintSource.DERIVED);
            }
        }
        for (String cleared : reduction.getCleared()) task.getConstraintSources().remove(cleared);
    }

    private void normalizeNearbyDefault(DecisionTaskState task, CriteriaMergeResult reduction) {
        DecisionConstraints constraints = reduction.getConstraints();
        if (Boolean.TRUE.equals(constraints.getNearby()) && (constraints.getRadiusKm() == null || constraints.getRadiusKm() <= 0D)) {
            constraints.setRadiusKm(3D);
            if (!constraints.getSystemNotes().contains("“附近”按默认 3km 解释")) constraints.getSystemNotes().add("“附近”按默认 3km 解释");
            if (!reduction.getReplaced().contains("radiusKm:-1.0->3.0")) reduction.getReplaced().add("radiusKm:-1.0->3.0");
            task.getConstraintSources().put("radiusKm", ConstraintSource.SYSTEM_DEFAULT);
        }
    }

    void markConstraintSourcesForTest(DecisionTaskState task, CriteriaMergeResult reduction) { markConstraintSources(task, reduction); }

    /**
     * Restores only BUSINESS_STATE fields from a historical snapshot. This always appends
     * a new version; diagnostics such as lastPolicyAction deliberately remain current.
     */
    public AiWorkingMemory restoreBusinessState(AiChatSession state, ConversationWorkingMemory source,
                                                Map<String, Object> metadata) {
        if (source == null) throw new IllegalArgumentException("Historical working memory cannot be empty");
        ConversationWorkingMemory current = workingMemory(state);
        ConversationWorkingMemory restored = copyBusinessState(source);
        restored.setLastPolicyAction(current.getLastPolicyAction());
        restored.setLastPolicyReason(current.getLastPolicyReason());
        normalize(restored);
        int expectedVersion = state.getVersion() == null ? 0 : state.getVersion();
        AiWorkingMemory committed = workingMemoryVersionService.append(state.getChatId(), state.getUserId(), expectedVersion,
                restored, ConversationEventType.STATE_REDUCED,
                java.util.Collections.<String, Object>singletonMap("reason", "BUSINESS_STATE_RESTORED"), metadata);
        state.setVersion(committed.getVersion()); state.setWorkingMemoryJson(committed.getMemoryJson());
        state.setActiveDecisionSessionId(restored.getActiveDecisionSessionId());
        state.setLastDecisionSessionId(restored.getLastDecisionSessionId());
        return committed;
    }

    /**
     * Returns the only mutable follow-up context for a decision. A different decision id
     * starts from that decision's immutable recommendation result before any tool delta is applied.
     */
    public AgentSessionContext contextForDecision(AiChatSession state, DecisionResponse decision) {
        if (decision == null || decision.getSessionId() == null) throw new IllegalArgumentException("决策会话不能为空");
        ConversationWorkingMemory memory = workingMemory(state);
        if (!decision.getSessionId().equals(latestSourceDecisionSessionId(memory))) {
            if (activateTaskForDecisionSession(memory, decision.getSessionId())) {
                updateWorkingMemory(state, memory);
            } else {
            snapshotDecision(state, decision);
            }
            memory = workingMemory(state);
            log.info("[AI][state] event=FOLLOW_UP_CONTEXT_REBOUND chatId={} sessionId={} candidates={}",
                    state.getChatId(), decision.getSessionId(), latestCandidatePool(memory).size());
        }
        return agentContext(state);
    }

    public void clearActiveDecision(AiChatSession state) { if (state.getActiveDecisionSessionId() != null) { ConversationWorkingMemory memory = workingMemory(state); memory.setActiveDecisionSessionId(null); updateWorkingMemory(state, memory); } }
    public void rememberLastDecision(AiChatSession state, Long decisionSessionId) { if (decisionSessionId != null && !decisionSessionId.equals(state.getLastDecisionSessionId())) { ConversationWorkingMemory memory = workingMemory(state); memory.setLastDecisionSessionId(decisionSessionId); updateWorkingMemory(state, memory); } }

    private void updateWorkingMemory(AiChatSession state, ConversationWorkingMemory memory) { persistMemory(state, memory, ConversationEventType.STATE_REDUCED, "WORKING_MEMORY_UPDATED"); }
    private void persistMemory(AiChatSession state, ConversationWorkingMemory memory, ConversationEventType eventType, String reason) {
        normalize(memory);
        String nextJson = writeWorkingMemory(memory);
        if (nextJson.equals(state.getWorkingMemoryJson())) return;
        int expectedVersion = state.getVersion() == null ? 0 : state.getVersion();
        AiWorkingMemory committed = workingMemoryVersionService.append(state.getChatId(), state.getUserId(), expectedVersion, memory,
                eventType, java.util.Collections.<String, Object>singletonMap("reason", reason), null);
        state.setVersion(committed.getVersion()); state.setWorkingMemoryJson(committed.getMemoryJson());
        state.setActiveDecisionSessionId(memory.getActiveDecisionSessionId()); state.setLastDecisionSessionId(memory.getLastDecisionSessionId());
    }

    private ConversationWorkingMemory copyBusinessState(ConversationWorkingMemory source) {
        try {
            ConversationWorkingMemory copy = objectMapper.readValue(writeWorkingMemory(source), ConversationWorkingMemory.class);
            // lastPolicy* is runtime diagnostics, not part of the BUSINESS_STATE restore scope.
            copy.setLastPolicyAction(null);
            copy.setLastPolicyReason(null);
            return copy;
        } catch (Exception e) {
            throw new IllegalStateException("Historical working memory cannot be copied", e);
        }
    }

    /**
     * A named destination is a search anchor, not browser GPS. Persist it independently
     * so a later turn cannot accidentally reuse coordinates from a different city.
     */
    public void applyNamedSearchLocation(AiChatSession state, DecisionConstraints criteria) {
        if (criteria == null || !hasText(criteria.getTargetCity())) return;
        ConversationWorkingMemory memory = workingMemory(state);
        ConversationLocationSlot target = ensureActiveTask(memory).getSearchLocation();
        boolean changed = !criteria.getTargetCity().equals(target.getCity())
                || !sameText(criteria.getTargetArea(), target.getDistrict());
        if (!changed && "RESOLVED_BY_NAME".equals(target.getStatus())
                && target.getLatitude() == null && target.getLongitude() == null) {
            return;
        }
        target.setStatus("RESOLVED_BY_NAME");
        target.setCity(criteria.getTargetCity());
        target.setDistrict(criteria.getTargetArea());
        target.setProvince(null);
        target.setLatitude(null);
        target.setLongitude(null);
        target.setSource("USER_EXPLICIT_DESTINATION");
        target.setCapturedAt(LocalDateTime.now());
        target.setExpiresAt(null);
        if (changed) {
            int previousCandidateCount = invalidateCandidatePool(memory);
            log.info("[AI][state] event=CASCADE_INVALIDATED chatId={} reason=TARGET_CITY_CHANGED previousCandidates={}",
                    state.getChatId(), previousCandidateCount);
        }
        updateWorkingMemory(state, memory);
        log.info("[AI][state] event=SEARCH_LOCATION_NAMED chatId={} city={} area={}", state.getChatId(),
                target.getCity(), target.getDistrict());
    }
    private String writeLegacySlots(ConversationWorkingMemory memory) { ConversationSlots slots = new ConversationSlots(); slots.setLocation(memory.getLocation()); slots.setPendingLocationCandidates(memory.getPendingLocationCandidates()); try { return objectMapper.writeValueAsString(slots); } catch (Exception e) { throw new IllegalStateException("Conversation location slots cannot be saved", e); } }
    private String writeWorkingMemory(ConversationWorkingMemory memory) { try { return objectMapper.writeValueAsString(memory); } catch (Exception e) { throw new IllegalStateException("Conversation working memory cannot be saved", e); } }
    private void normalize(ConversationWorkingMemory memory) { if (memory.getLocation() == null) memory.setLocation(new ConversationLocationSlot()); if (memory.getTasks() == null) memory.setTasks(new ArrayList<DecisionTaskState>()); for (DecisionTaskState task : memory.getTasks()) { if (task.getCriteria() == null) task.setCriteria(new DecisionConstraints()); if (task.getSearchLocation() == null) task.setSearchLocation(new ConversationLocationSlot()); if (task.getRecommendationBatches() == null) task.setRecommendationBatches(new ArrayList<RecommendationBatch>()); } if (memory.getPendingLocationCandidates() == null) memory.setPendingLocationCandidates(new ArrayList<ResolvedLocationCandidate>()); if (!hasText(memory.getDialogPhase())) memory.setDialogPhase("IDLE"); if (!hasText(memory.getLastPolicyAction())) memory.setLastPolicyAction("NONE"); }
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
        return "targetCity".equals(field) || "targetArea".equals(field) || "cuisine".equals(field) || "budgetPerPerson".equals(field) || "radiusKm".equals(field)
                || "nearby".equals(field) || "arrivalTime".equals(field) || "occasion".equals(field)
                || "quiet".equals(field) || "avoidQueue".equals(field) || "hardConstraints".equals(field)
                || "softPreferences".equals(field);
    }

    /** Keeps a named destination physically separate from browser-provided coordinates. */
    private void synchronizeNamedSearchLocation(AiChatSession state, ConversationWorkingMemory memory,
                                                CriteriaMergeResult reduction) {
        DecisionConstraints criteria = reduction.getConstraints();
        if (!hasText(criteria.getTargetCity()) && !hasText(criteria.getTargetArea())) {
            if (containsClearedField(reduction.getCleared(), "targetCity") || containsClearedField(reduction.getCleared(), "targetArea")) {
                DecisionTaskState task = activeTask(memory);
                if (task != null) clearLocation(task.getSearchLocation(), "MISSING");
                log.info("[AI][state] event=NAMED_SEARCH_LOCATION_CLEARED chatId={} action=USE_DEVICE_LOCATION_IF_AUTHORIZED", state.getChatId());
            }
            return;
        }
        if (!containsField(reduction.getReplaced(), "targetCity")
                && !containsField(reduction.getReplaced(), "targetArea")) return;

        ConversationLocationSlot target = ensureActiveTask(memory).getSearchLocation();
        clearLocation(target, "RESOLVED_BY_NAME");
        target.setCity(criteria.getTargetCity());
        target.setDistrict(criteria.getTargetArea());
        target.setSource("USER_EXPLICIT");
        target.setCapturedAt(LocalDateTime.now());
        log.info("[AI][state] event=NAMED_SEARCH_LOCATION_REDUCED chatId={} targetCity={} targetArea={} action=CLEAR_TARGET_COORDINATES",
                state.getChatId(), criteria.getTargetCity(), criteria.getTargetArea());
    }

    private boolean containsField(List<String> changes, String field) {
        if (changes == null) return false;
        for (String change : changes) {
            if (change != null && change.startsWith(field + ":")) return true;
        }
        return false;
    }
    private boolean containsClearedField(List<String> changes, String field) {
        return changes != null && changes.contains(field);
    }
    private int invalidateCandidatePool(ConversationWorkingMemory memory) {
        int previousCandidateCount = latestCandidatePool(memory).size();
        appendRecommendationBatch(ensureActiveTask(memory), null, new ArrayList<DecisionRecommendation>());
        memory.setFocusedShopId(null);
        memory.setFocusedShopName(null);
        return previousCandidateCount;
    }

    private void appendRecommendationBatch(DecisionTaskState task, Long sessionId, List<DecisionRecommendation> recommendations) {
        RecommendationBatch batch = new RecommendationBatch();
        batch.setDecisionSessionId(sessionId);
        for (DecisionRecommendation item : recommendations) {
            if (item == null) continue;
            RecommendationCandidateRef ref = new RecommendationCandidateRef();
            ref.setShopId(item.getShopId()); ref.setShopName(item.getShopName());
            ref.setPricePerPerson(item.getAvgPrice()); ref.setDistanceKm(item.getDistanceKm());
            batch.getCandidates().add(ref);
        }
        task.getRecommendationBatches().add(batch);
    }

    private List<DecisionRecommendation> recommendations(RecommendationBatch batch) {
        List<DecisionRecommendation> result = new ArrayList<DecisionRecommendation>();
        if (batch == null || batch.getCandidates() == null) return result;
        for (RecommendationCandidateRef ref : batch.getCandidates()) {
            DecisionRecommendation item = new DecisionRecommendation();
            item.setShopId(ref.getShopId()); item.setShopName(ref.getShopName());
            item.setAvgPrice(ref.getPricePerPerson()); item.setDistanceKm(ref.getDistanceKm());
            result.add(item);
        }
        return result;
    }

    private boolean sameCandidateIds(List<DecisionRecommendation> left, List<DecisionRecommendation> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) if (!java.util.Objects.equals(left.get(i).getShopId(), right.get(i).getShopId())) return false;
        return true;
    }

    private boolean sameBatch(DecisionTaskState task, Long sessionId, List<DecisionRecommendation> candidates) {
        if (task.getRecommendationBatches() == null || task.getRecommendationBatches().isEmpty()) return false;
        RecommendationBatch latest = task.getRecommendationBatches().get(task.getRecommendationBatches().size() - 1);
        return java.util.Objects.equals(latest.getDecisionSessionId(), sessionId)
                && sameCandidateIds(recommendations(latest), candidates);
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
    private boolean sameText(String left, String right) { return java.util.Objects.equals(left == null ? "" : left, right == null ? "" : right); }
}
