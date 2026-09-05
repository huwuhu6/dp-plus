package com.hmdp.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Durable, chat-scoped business state. Message history is only supporting context;
 * this object is the source of truth for references and incremental search criteria.
 */
@Data
public class ConversationWorkingMemory {
    private int schemaVersion = 2;
    /** Internal recommendation-task references; chatId/traceId/eventId remain runtime identities. */
    private Long activeDecisionSessionId;
    private Long lastDecisionSessionId;
    /** Device-provided location. It must never be overwritten by a named search destination. */
    private ConversationLocationSlot deviceLocation = new ConversationLocationSlot();
    /** Explicit destination used by the current recommendation task, such as "重庆" or "上街大学城". */
    private List<ResolvedLocationCandidate> pendingLocationCandidates = new ArrayList<ResolvedLocationCandidate>();
    private String activeTaskId;
    private List<DecisionTaskState> tasks = new ArrayList<DecisionTaskState>();
    private List<DecisionRecommendation> candidatePool = new ArrayList<DecisionRecommendation>();
    /** Shop ids already presented in the current retrieval domain; used for explicit refresh requests. */
    private List<Long> shownShopIds = new ArrayList<Long>();
    private Long focusedShopId;
    private String focusedShopName;
    private String dialogPhase = "IDLE";
    private Long sourceDecisionSessionId;
    private String lastPolicyAction = "NONE";
    private String lastPolicyReason;

    /** V2 canonical criteria live in the active task; no flat criteria is persisted. */
    public DecisionTaskState activeTask() {
        for (DecisionTaskState task : tasks) if (task.getTaskId().equals(activeTaskId)) return task;
        DecisionTaskState task = new DecisionTaskState();
        task.setTaskId(UUID.randomUUID().toString());
        task.setTitle("当前推荐");
        tasks.add(task); activeTaskId = task.getTaskId();
        return task;
    }
    // Transitional derived accessors for entity-context callers. They do not store a second criteria copy.
    public DecisionConstraints getActiveCriteria() { return activeTask().getCriteria(); }
    public void setActiveCriteria(DecisionConstraints criteria) { activeTask().setCriteria(criteria == null ? new DecisionConstraints() : criteria); }
    public ConversationLocationSlot getLocation() { return deviceLocation; }
    public void setLocation(ConversationLocationSlot location) { this.deviceLocation = location; }
    public ConversationLocationSlot getSearchLocation() { return activeTask().getSearchLocation(); }
    public void setSearchLocation(ConversationLocationSlot location) { activeTask().setSearchLocation(location); }
}
