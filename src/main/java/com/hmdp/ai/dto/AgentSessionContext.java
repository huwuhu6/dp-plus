package com.hmdp.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentSessionContext {
    private Integer turnNo = 0;
    /** Durable version this runtime projection was created from. */
    private Integer baseWorkingMemoryVersion;
    private Long focusedShopId;
    private String focusedShopName;
    /** Snapshot of the durable candidate pool; it is not the historical shown set. */
    private List<DecisionRecommendation> candidatePoolSnapshot = new ArrayList<DecisionRecommendation>();
    /** Snapshot copied from Working Memory; never derive it from candidatePoolSnapshot. */
    private List<Long> shownShopIdsSnapshot = new ArrayList<Long>();
    private DecisionRequest decisionRequest;
    private DecisionConstraints decisionConstraints;
}
