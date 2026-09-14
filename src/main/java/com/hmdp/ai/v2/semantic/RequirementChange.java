package com.hmdp.ai.v2.semantic;
import java.util.Set;

/** A deliberately closed set prevents a Field/Object EAV model from leaking into the domain. */
public sealed interface RequirementChange permits RequirementChange.CriteriaPatch, RequirementChange.RelativePreference,
        RequirementChange.RelaxationAuthorization, RequirementChange.RequirementLock, RequirementChange.ConditionalRequirementChange {
    /** null in fragment means untouched; explicit removal must be present in cleared. */
    record CriteriaPatch(DiningCriteria fragment, Set<ClearedCriterion> cleared) implements RequirementChange {
        public CriteriaPatch {
            fragment = fragment == null ? DiningCriteria.empty() : fragment;
            cleared = cleared == null ? Set.of() : Set.copyOf(cleared);
            if (cleared.contains(ClearedCriterion.BUDGET) && fragment.budget() != null || cleared.contains(ClearedCriterion.CUISINE) && fragment.cuisine() != null
                    || cleared.contains(ClearedCriterion.LOCATION) && fragment.location() != null || cleared.contains(ClearedCriterion.DISTANCE) && fragment.distance() != null
                    || cleared.contains(ClearedCriterion.DINING_TIME) && fragment.diningTime() != null || cleared.contains(ClearedCriterion.SEMANTIC_PREFERENCES) && !fragment.preferences().equals(DiningCriteria.SemanticPreferences.empty()))
                throw new IllegalArgumentException("criterion cannot be patched and cleared in one turn");
        }
    }
    /** “便宜一点” is relative user intent, never an invented absolute budget. */
    record RelativePreference(DiningCriteria.PreferenceDimension dimension, Direction direction) implements RequirementChange { }
    record RelaxationAuthorization(DiningCriteria.PreferenceDimension dimension) implements RequirementChange { }
    record RequirementLock(DiningCriteria.PreferenceDimension dimension) implements RequirementChange { }
    /** This branch is excluded from PreReducer because its truth needs execution evidence. */
    record ConditionalRequirementChange(String observedRequestId, ObservationPredicate predicate, CriteriaPatch change) implements RequirementChange { }
    enum ClearedCriterion { BUDGET, CUISINE, LOCATION, DISTANCE, DINING_TIME, SEMANTIC_PREFERENCES }
    enum Direction { LOWER, HIGHER }
}
