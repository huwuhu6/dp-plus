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
    /** Internal recommendation-task references; chatId/traceId/eventId remain runtime identities. */
    private Long activeDecisionSessionId;
    private Long lastDecisionSessionId;
    /** Device-provided location. It must never be overwritten by a named search destination. */
    private ConversationLocationSlot location = new ConversationLocationSlot();
    /** Explicit destination used by the current recommendation task, such as "重庆" or "上街大学城". */
    private ConversationLocationSlot searchLocation = new ConversationLocationSlot();
    private List<ResolvedLocationCandidate> pendingLocationCandidates = new ArrayList<ResolvedLocationCandidate>();
    private DecisionConstraints activeCriteria = new DecisionConstraints();
    private List<DecisionRecommendation> candidatePool = new ArrayList<DecisionRecommendation>();
    private Long focusedShopId;
    private String focusedShopName;
    private String dialogPhase = "IDLE";
    private Long sourceDecisionSessionId;
    private String lastPolicyAction = "NONE";
    private String lastPolicyReason;
}
