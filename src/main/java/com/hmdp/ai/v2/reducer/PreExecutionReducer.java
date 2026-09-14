package com.hmdp.ai.v2.reducer;
import com.hmdp.ai.v2.semantic.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
/** Applies only explicit facts that do not depend on tools. Conditional fallback deliberately stays out of this reducer. */
public final class PreExecutionReducer {
    public V2TaskState reduce(V2TaskState previous, TurnSemantics turn) {
        DiningCriteria criteria = previous.criteria(); List<RequirementChange.RelativePreference> relative = new ArrayList<>(previous.relativePreferences());
        Set<DiningCriteria.PreferenceDimension> relaxable = new HashSet<>(previous.relaxable()); Set<DiningCriteria.PreferenceDimension> locked = new HashSet<>(previous.locked());
        for (RequirementChange change : turn.requirementChanges()) switch (change) {
            case RequirementChange.CriteriaPatch patch -> criteria = merge(criteria, patch);
            case RequirementChange.RelativePreference preference -> relative.add(preference);
            case RequirementChange.RelaxationAuthorization authorization -> relaxable.add(authorization.dimension());
            case RequirementChange.RequirementLock lock -> locked.add(lock.dimension());
            case RequirementChange.ConditionalRequirementChange ignored -> { /* external observation decides whether it may become durable */ }
        };
        return new V2TaskState(criteria, relative, relaxable, locked);
    }
    private DiningCriteria merge(DiningCriteria base, RequirementChange.CriteriaPatch patch) {
        DiningCriteria fragment = patch.fragment(); Set<RequirementChange.ClearedCriterion> clear = patch.cleared();
        return new DiningCriteria(clear.contains(RequirementChange.ClearedCriterion.LOCATION) ? null : choose(fragment.location(), base.location()),
                clear.contains(RequirementChange.ClearedCriterion.CUISINE) ? null : choose(fragment.cuisine(), base.cuisine()),
                clear.contains(RequirementChange.ClearedCriterion.BUDGET) ? null : choose(fragment.budget(), base.budget()),
                clear.contains(RequirementChange.ClearedCriterion.DISTANCE) ? null : choose(fragment.distance(), base.distance()),
                clear.contains(RequirementChange.ClearedCriterion.DINING_TIME) ? null : choose(fragment.diningTime(), base.diningTime()),
                clear.contains(RequirementChange.ClearedCriterion.SEMANTIC_PREFERENCES) ? DiningCriteria.SemanticPreferences.empty() : choose(fragment.preferences(), base.preferences()));
    }
    private <T> T choose(T patch, T current) { return patch == null ? current : patch; }
}
