package com.hmdp.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ConversationSlots {
    private ConversationLocationSlot location = new ConversationLocationSlot();
    private List<ResolvedLocationCandidate> pendingLocationCandidates = new ArrayList<>();
}
