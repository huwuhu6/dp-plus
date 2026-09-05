package com.hmdp.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

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
    private Long focusedShopId;
    private String focusedShopName;
    private String dialogPhase = "IDLE";
    private String lastPolicyAction = "NONE";
    private String lastPolicyReason;

    /** Read-only lookup. State readers must not create a task as a side effect. */
    public DecisionTaskState activeTask() {
        if (tasks == null || activeTaskId == null) return null;
        for (DecisionTaskState task : tasks) if (task != null && activeTaskId.equals(task.getTaskId())) return task;
        return null;
    }

    /** Write-path helper for a conversation whose first recommendation task is being created. */
    public DecisionTaskState ensureActiveTask() {
        DecisionTaskState existing = activeTask();
        if (existing != null) return existing;
        DecisionTaskState task = new DecisionTaskState();
        task.setTaskId(java.util.UUID.randomUUID().toString());
        task.setTitle("当前推荐");
        if (tasks == null) tasks = new ArrayList<DecisionTaskState>();
        tasks.add(task); activeTaskId = task.getTaskId();
        return task;
    }
    public ConversationLocationSlot getLocation() { return deviceLocation; }
    public void setLocation(ConversationLocationSlot location) { this.deviceLocation = location; }
    /** Deprecated read projection for legacy location policy code; never creates a task. */
    public ConversationLocationSlot getSearchLocation() { DecisionTaskState task = activeTask(); return task == null ? null : task.getSearchLocation(); }
}
