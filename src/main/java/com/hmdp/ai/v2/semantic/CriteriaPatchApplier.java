package com.hmdp.ai.v2.semantic;

/** The only authority for applying a partial criteria change to canonical task state. */
public final class CriteriaPatchApplier {
    private CriteriaPatchApplier() { }
    public static DiningCriteria apply(DiningCriteria base, RequirementChange.CriteriaPatch change) {
        DiningCriteriaPatch patch = change.patch(); var clear = change.cleared();
        return new DiningCriteria(clear.contains(RequirementChange.ClearedCriterion.LOCATION) ? null : choose(patch.location(), base.location()),
                clear.contains(RequirementChange.ClearedCriterion.CUISINE) ? null : choose(patch.cuisine(), base.cuisine()),
                clear.contains(RequirementChange.ClearedCriterion.BUDGET) ? null : choose(patch.budget(), base.budget()),
                clear.contains(RequirementChange.ClearedCriterion.DISTANCE) ? null : choose(patch.distance(), base.distance()),
                clear.contains(RequirementChange.ClearedCriterion.DINING_TIME) ? null : choose(patch.diningTime(), base.diningTime()),
                clear.contains(RequirementChange.ClearedCriterion.SEMANTIC_PREFERENCES) ? DiningCriteria.SemanticPreferences.empty() : choose(patch.semanticPreferences(), base.preferences()));
    }
    private static <T> T choose(T patch, T base) { return patch == null ? base : patch; }
}
