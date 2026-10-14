package com.hmdp.ai.v2.semantic;

/** The only authority for applying a partial criteria change to canonical task state. */
public final class CriteriaPatchApplier {
    private CriteriaPatchApplier() { }
    public static DiningCriteria apply(DiningCriteria base, RequirementChange.CriteriaPatch change) {
        DiningCriteriaPatch patch = change.patch(); var clear = change.cleared();
        var dietary=clear.contains(RequirementChange.ClearedCriterion.DIETARY_RESTRICTIONS)?null:
                DiningCriteria.DietaryRestrictions.merge(base.dietaryRestrictions(),patch.dietaryRestrictions());
        var preferences=clear.contains(RequirementChange.ClearedCriterion.SEMANTIC_PREFERENCES)?DiningCriteria.SemanticPreferences.empty():choose(patch.semanticPreferences(),base.preferences());
        // 新硬辣度限制覆盖旧的求辣偏好，其他环境、价格等偏好仍保留。
        if(dietary!=null && dietary.spiceRequirement()!=null && preferences!=null)
            preferences=new DiningCriteria.SemanticPreferences(preferences.values().stream().filter(p->!p.criterion().contains("辣")
                    || p.criterion().matches("(?s).*(?:不|少|忌).*" )).toList(),preferences.priorityOrder());
        return new DiningCriteria(clear.contains(RequirementChange.ClearedCriterion.LOCATION) ? null : choose(patch.location(), base.location()),
                clear.contains(RequirementChange.ClearedCriterion.CUISINE) ? null : choose(patch.cuisine(), base.cuisine()),
                clear.contains(RequirementChange.ClearedCriterion.BUDGET) ? null : choose(patch.budget(), base.budget()),
                clear.contains(RequirementChange.ClearedCriterion.DISTANCE) ? null : choose(patch.distance(), base.distance()),
                clear.contains(RequirementChange.ClearedCriterion.DINING_TIME) ? null : choose(patch.diningTime(), base.diningTime()),
                preferences,clear.contains(RequirementChange.ClearedCriterion.PARTY_SIZE) ? null : choose(patch.partySize(), base.partySize()),dietary);
    }
    private static <T> T choose(T patch, T base) { return patch == null ? base : patch; }
}
