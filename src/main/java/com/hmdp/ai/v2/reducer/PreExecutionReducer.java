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
            case RequirementChange.CriteriaPatch patch -> criteria = CriteriaPatchApplier.apply(criteria, patch);
            case RequirementChange.RelativePreference preference -> relative.add(preference);
            case RequirementChange.RelaxationAuthorization authorization -> relaxable.add(authorization.dimension());
            case RequirementChange.RequirementLock lock -> locked.add(lock.dimension());
            case RequirementChange.ConditionalRequirementChange ignored -> { /* external observation decides whether it may become durable */ }
        };
        return new V2TaskState(criteria, relative, relaxable, locked);
    }
}
