package com.hmdp.ai.v2.grounding;
/** Task selector misses and ambiguous matches are clarification, not failures of the request pipeline. */
public sealed interface EffectiveTaskContextResult permits EffectiveTaskContextResult.Resolved, EffectiveTaskContextResult.NeedsClarification {
    record Resolved(EffectiveTaskContext context) implements EffectiveTaskContextResult { }
    record NeedsClarification(Ambiguity ambiguity) implements EffectiveTaskContextResult { }
}
