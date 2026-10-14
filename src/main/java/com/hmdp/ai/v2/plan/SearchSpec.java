package com.hmdp.ai.v2.plan;
import com.hmdp.ai.v2.grounding.GroundedReference;
import com.hmdp.ai.v2.semantic.DiningCriteria;
import com.hmdp.ai.v2.semantic.RequirementChange;
import java.util.List;
import java.util.Set;
/** Frozen causal input for search. Derived ranking preferences are not written back to DiningCriteria. */
public record SearchSpec(int baseMemoryVersion, String taskId, ExecutionAction.SearchKind kind, int count,
                         DiningCriteria criteria, List<RequirementChange.RelativePreference> relativePreferences,
                         Set<Long> excludedShopIds, GroundedReference.ShopIdentity anchor, SearchAnchor searchAnchor,SearchRadiusPolicy radiusPolicy) {
    public SearchSpec(int version,String taskId,ExecutionAction.SearchKind kind,int count,DiningCriteria criteria,List<RequirementChange.RelativePreference> relative,
                      Set<Long> excluded,GroundedReference.ShopIdentity anchor,SearchAnchor searchAnchor){this(version,taskId,kind,count,criteria,relative,excluded,anchor,searchAnchor,SearchRadiusPolicy.forSearch(criteria,searchAnchor));}
    public SearchSpec { relativePreferences = List.copyOf(relativePreferences); excludedShopIds = Set.copyOf(excludedShopIds); }
}
