package com.hmdp.ai.v2.semantic;

import java.util.List;

public sealed interface UserRequest permits UserRequest.RecommendationRequest, UserRequest.AlternativesRequest,
        UserRequest.FactQueryRequest, UserRequest.CompareRequest, UserRequest.SimilarRequest,
        UserRequest.SelectRequest, UserRequest.ExploreRequest, UserRequest.GeneralKnowledgeRequest {
    String requestId();
    record RecommendationRequest(String requestId) implements UserRequest { }
    record AlternativesRequest(String requestId, int count) implements UserRequest { }
    record FactQueryRequest(String requestId, EntityReference target, FactType fact) implements UserRequest { }
    record CompareRequest(String requestId, List<EntityReference> targets, List<FactType> dimensions) implements UserRequest { public CompareRequest { targets = List.copyOf(targets); dimensions = List.copyOf(dimensions); } }
    record SimilarRequest(String requestId, EntityReference anchor) implements UserRequest { }
    record SelectRequest(String requestId, EntityReference target) implements UserRequest { }
    /** unboundedContinuation means the user delegated choosing unknown next research steps; then target may be null. */
    record ExploreRequest(String requestId, EntityReference target, boolean unboundedContinuation) implements UserRequest { }
    record GeneralKnowledgeRequest(String requestId, String topic) implements UserRequest { }
    enum FactType { SOCKET, PRIVATE_ROOM, REVIEW, VOUCHER, DISTANCE, QUEUE, DETAIL, EVIDENCE }
}
