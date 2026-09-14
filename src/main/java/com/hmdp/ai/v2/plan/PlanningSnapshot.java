package com.hmdp.ai.v2.plan;
import com.hmdp.ai.v2.semantic.DiningCriteria;
import com.hmdp.ai.v2.semantic.RequirementChange;
import java.util.List;
import java.util.Set;
/** Snapshot is taken only after the pre-mutation OCC commit. Plan execution must not reread mutable WorkingMemory. */
public record PlanningSnapshot(int baseMemoryVersion, String taskId, DiningCriteria criteria,
                               List<RequirementChange.RelativePreference> relativePreferences,
                               Set<Long> rejectedShopIds, String searchLocation) {
    public PlanningSnapshot { relativePreferences = relativePreferences == null ? List.of() : List.copyOf(relativePreferences); rejectedShopIds = rejectedShopIds == null ? Set.of() : Set.copyOf(rejectedShopIds); }
}
