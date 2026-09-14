package com.hmdp.ai.v2.runtime;

import com.hmdp.ai.v2.plan.ExecutionAction;
import com.hmdp.ai.v2.plan.SearchAnchor;
import com.hmdp.ai.v2.semantic.DiningCriteria;
import com.hmdp.ai.v2.semantic.RequirementChange;
import java.util.List;
import java.util.Set;

/** Typed retrieval input shared by the V2 runtime and the legacy decision shell. */
public record ShopRetrievalRequest(DiningCriteria criteria,
                                   List<RequirementChange.RelativePreference> relativePreferences,
                                   ExecutionAction.SearchKind kind, Set<Long> excludedShopIds,
                                   Long similarityAnchorShopId, SearchAnchor searchAnchor,
                                   int count, String semanticQuery) {
    public ShopRetrievalRequest {
        criteria = criteria == null ? DiningCriteria.empty() : criteria;
        relativePreferences = relativePreferences == null ? List.of() : List.copyOf(relativePreferences);
        excludedShopIds = excludedShopIds == null ? Set.of() : Set.copyOf(excludedShopIds);
    }
}
