package com.hmdp.ai.v2.reducer;
import com.hmdp.ai.v2.semantic.DiningCriteria;
import com.hmdp.ai.v2.semantic.RequirementChange;
import java.util.List;
import java.util.Set;
/** Immutable V2 reducer state; runtime persistence will project this into the single canonical WorkingMemory schema. */
public record V2TaskState(DiningCriteria criteria, List<RequirementChange.RelativePreference> relativePreferences,
                          Set<DiningCriteria.PreferenceDimension> relaxable, Set<DiningCriteria.PreferenceDimension> locked) {
    public V2TaskState { relativePreferences = relativePreferences == null ? List.of() : List.copyOf(relativePreferences); relaxable = relaxable == null ? Set.of() : Set.copyOf(relaxable); locked = locked == null ? Set.of() : Set.copyOf(locked); }
}
