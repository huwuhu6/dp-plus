package com.hmdp.ai.v2.grounding;
import java.util.List;
/** Unresolved user references are normal clarification outcomes, never HTTP 500 exceptions. */
public sealed interface GroundingResult permits GroundingResult.Grounded, GroundingResult.NeedsClarification {
    record Grounded(GroundedTurn turn) implements GroundingResult { }
    record NeedsClarification(List<Ambiguity> ambiguities) implements GroundingResult { public NeedsClarification { ambiguities = List.copyOf(ambiguities); } }
}
