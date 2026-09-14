package com.hmdp.ai.v2.plan;
import com.hmdp.ai.v2.semantic.DiningCriteria;
import com.hmdp.ai.v2.semantic.RequirementChange;
import java.util.List;
import java.util.Set;
/** Snapshot is taken only after the pre-mutation OCC commit. Plan execution must not reread mutable WorkingMemory. */
public record PlanningSnapshot(int baseMemoryVersion, String taskId, DiningCriteria criteria,
                               List<RequirementChange.RelativePreference> relativePreferences,
                               Set<Long> rejectedShopIds, Set<DiningCriteria.PreferenceDimension> relaxable,
                               Set<DiningCriteria.PreferenceDimension> locked, SearchAnchor searchAnchor,
                               Set<Long> currentVisibleShopIds) {
    public PlanningSnapshot { relativePreferences = relativePreferences == null ? List.of() : List.copyOf(relativePreferences); rejectedShopIds = rejectedShopIds == null ? Set.of() : Set.copyOf(rejectedShopIds); relaxable = relaxable == null ? Set.of() : Set.copyOf(relaxable); locked = locked == null ? Set.of() : Set.copyOf(locked); currentVisibleShopIds = currentVisibleShopIds == null ? Set.of() : Set.copyOf(currentVisibleShopIds); }
    public PlanningSnapshot(int baseMemoryVersion, String taskId, DiningCriteria criteria, List<RequirementChange.RelativePreference> relativePreferences,
                            Set<Long> rejectedShopIds, Set<DiningCriteria.PreferenceDimension> relaxable,
                            Set<DiningCriteria.PreferenceDimension> locked, SearchAnchor searchAnchor) {
        this(baseMemoryVersion, taskId, criteria, relativePreferences, rejectedShopIds, relaxable, locked, searchAnchor, Set.of());
    }
}
