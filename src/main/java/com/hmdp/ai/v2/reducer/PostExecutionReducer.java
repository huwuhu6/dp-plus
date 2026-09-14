package com.hmdp.ai.v2.reducer;
import com.hmdp.ai.v2.plan.ExecutionAction;
import com.hmdp.ai.v2.semantic.RequirementChange;
/** Conditional criteria enter durable state only after its guarded search actually emitted this effect. */
public final class PostExecutionReducer {
    public V2TaskState apply(V2TaskState previous, ExecutionAction.DomainEffect effect) {
        if (!(effect instanceof ExecutionAction.DomainEffect.ConditionalCriteriaApplied applied)) return previous;
        return new V2TaskState(com.hmdp.ai.v2.semantic.CriteriaPatchApplier.apply(previous.criteria(), applied.patch()), previous.relativePreferences(), previous.relaxable(), previous.locked());
    }
}
