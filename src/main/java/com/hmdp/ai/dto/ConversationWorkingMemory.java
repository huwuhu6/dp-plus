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
    private ConversationLocationSlot location = new ConversationLocationSlot();
    private List<ResolvedLocationCandidate> pendingLocationCandidates = new ArrayList<ResolvedLocationCandidate>();
    private DecisionConstraints activeCriteria = new DecisionConstraints();
    private List<DecisionRecommendation> candidatePool = new ArrayList<DecisionRecommendation>();
    private Long focusedShopId;
    private String focusedShopName;
    private String dialogPhase = "IDLE";
    private Long sourceDecisionSessionId;
}
