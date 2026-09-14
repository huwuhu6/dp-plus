package com.hmdp.ai.v2.plan;
import com.hmdp.ai.v2.grounding.GroundedReference;
import com.hmdp.ai.v2.semantic.DiningCriteria;
import com.hmdp.ai.v2.semantic.RequirementChange;
import java.util.List;
import java.util.Set;
/** Frozen causal input for search. Derived ranking preferences are not written back to DiningCriteria. */
public record SearchSpec(int baseMemoryVersion, String taskId, ExecutionAction.SearchKind kind, int count,
                         DiningCriteria criteria, List<RequirementChange.RelativePreference> relativePreferences,
                         Set<Long> excludedShopIds, GroundedReference.ShopIdentity anchor) {
    public SearchSpec { relativePreferences = List.copyOf(relativePreferences); excludedShopIds = Set.copyOf(excludedShopIds); }
}
