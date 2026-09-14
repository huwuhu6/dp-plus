package com.hmdp.ai.v2.plan;

import com.hmdp.ai.v2.grounding.GroundedReference;
import com.hmdp.ai.v2.semantic.UserRequest;
import java.util.List;

public sealed interface ExecutionAction permits ExecutionAction.ToolAction, ExecutionAction.SearchAction,
        ExecutionAction.CompareAction, ExecutionAction.EmitDomainEffectAction {
    String requestId();
    record ToolAction(String requestId, GroundedReference.ShopIdentity shop, UserRequest.FactType fact) implements ExecutionAction { }
    record SearchAction(String requestId, SearchSpec spec) implements ExecutionAction { }
    record CompareAction(String requestId, List<GroundedReference.ShopIdentity> shops, List<UserRequest.FactType> dimensions) implements ExecutionAction { public CompareAction { shops = List.copyOf(shops); dimensions = List.copyOf(dimensions); } }
    /** Executor emits effects; only a post-execution reducer may commit durable state through OCC. */
    record EmitDomainEffectAction(String requestId, DomainEffect effect) implements ExecutionAction { }
    enum SearchKind { RECOMMENDATIONS, ALTERNATIVES, SIMILAR }
    sealed interface DomainEffect permits DomainEffect.CandidateSelected, DomainEffect.CuisineChangedTo {
        record CandidateSelected(long shopId) implements DomainEffect { }
        record CuisineChangedTo(String cuisine) implements DomainEffect { }
    }
}
