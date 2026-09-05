package com.hmdp.ai.service;

import com.hmdp.ai.dto.ConversationWorkingMemory;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.dto.DecisionTaskState;
import com.hmdp.ai.dto.RecommendationBatch;
import com.hmdp.ai.dto.RecommendationCandidateRef;
import java.util.List;

/** Test-only V2 fixture: it constructs the same Task/Batch shape production persists. */
public final class TestTaskFixture {
    private TestTaskFixture() { }
    public static DecisionTaskState task(ConversationWorkingMemory memory) { return memory.ensureActiveTask(); }
    public static void append(ConversationWorkingMemory memory, Long sessionId, List<DecisionRecommendation> recommendations) {
        RecommendationBatch batch = new RecommendationBatch(); batch.setDecisionSessionId(sessionId);
        for (DecisionRecommendation item : recommendations) {
            RecommendationCandidateRef ref = new RecommendationCandidateRef();
            ref.setShopId(item.getShopId()); ref.setShopName(item.getShopName());
            ref.setPricePerPerson(item.getAvgPrice()); ref.setDistanceKm(item.getDistanceKm());
            batch.getCandidates().add(ref);
        }
        task(memory).getRecommendationBatches().add(batch);
    }
}
