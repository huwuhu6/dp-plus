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
    /** Task-local recommendation history used for ordinal references; never flatten this into shown IDs. */
    private List<RecommendationBatch> recommendationBatches = new ArrayList<RecommendationBatch>();
    /** Parsed once at the chat boundary and reused by rewrite and tool binding. */
    private List<ReferenceIntent> referenceIntents = new ArrayList<ReferenceIntent>();
    /** Immutable bindings captured before criteria mutation in the current turn. */
    private List<ResolvedShopReference> resolvedReferences = new ArrayList<ResolvedShopReference>();
    private String referenceIntentMessage;
    private DecisionRequest decisionRequest;
    private DecisionConstraints decisionConstraints;
}
