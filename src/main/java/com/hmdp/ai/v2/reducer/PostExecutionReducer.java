package com.hmdp.ai.v2.reducer;
import com.hmdp.ai.v2.plan.ExecutionAction;
import com.hmdp.ai.v2.semantic.RequirementChange;
/** Conditional criteria enter durable state only after its guarded search actually emitted this effect. */
public final class PostExecutionReducer {
    public V2TaskState applyConditionalCuisine(V2TaskState previous, RequirementChange.CriteriaPatch patch, ExecutionAction.DomainEffect effect) {
        if (!(effect instanceof ExecutionAction.DomainEffect.CuisineChangedTo)) return previous;
        return new PreExecutionReducer().reduce(previous, new com.hmdp.ai.v2.semantic.TurnSemantics(com.hmdp.ai.v2.semantic.TaskDirective.CONTINUE, java.util.List.of(patch), java.util.List.of(), java.util.List.of(), java.util.List.of()));
    }
}
