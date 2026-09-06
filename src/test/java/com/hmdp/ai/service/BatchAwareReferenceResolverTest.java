package com.hmdp.ai.service;

import com.hmdp.ai.dto.AgentSessionContext;
import com.hmdp.ai.dto.RecommendationBatch;
import com.hmdp.ai.dto.RecommendationCandidateRef;
import com.hmdp.ai.dto.ReferenceIntent;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class BatchAwareReferenceResolverTest {
    private final BatchAwareReferenceResolver resolver = new BatchAwareReferenceResolver();

    @Test
    void resolvesOrdinalFromLatestBatchAndEarliestQualifiedBatch() {
        AgentSessionContext context = context(batch(10L, 101L, 102L), batch(11L, 201L, 202L));
        assertEquals(202L, resolver.resolve(intent(ReferenceIntent.Scope.LATEST, 2), context).shopId());
        var earliest = resolver.resolve(intent(ReferenceIntent.Scope.EARLIEST, 1), context);
        assertEquals(101L, earliest.shopId());
        assertEquals(10L, earliest.batch().getDecisionSessionId());
    }

    @Test
    void treatsEmptyLatestBatchAsInvalidationBoundary() {
        AgentSessionContext context = context(batch(10L, 101L), batch(11L));
        assertNull(resolver.resolve(intent(ReferenceIntent.Scope.LATEST, 1), context));
        assertEquals(101L, resolver.resolve(intent(ReferenceIntent.Scope.EARLIEST, 1), context).shopId());
    }

    private ReferenceIntent intent(ReferenceIntent.Scope scope, int ordinal) {
        return new ReferenceIntent(scope, ordinal, "ref", 0, 3);
    }

    private AgentSessionContext context(RecommendationBatch... batches) {
        AgentSessionContext context = new AgentSessionContext();
        context.setRecommendationBatches(Arrays.asList(batches));
        return context;
    }

    private RecommendationBatch batch(Long sessionId, Long... ids) {
        RecommendationBatch batch = new RecommendationBatch(); batch.setDecisionSessionId(sessionId);
        for (Long id : ids) { RecommendationCandidateRef ref = new RecommendationCandidateRef(); ref.setShopId(id); ref.setShopName("shop-" + id); batch.getCandidates().add(ref); }
        return batch;
    }
}
