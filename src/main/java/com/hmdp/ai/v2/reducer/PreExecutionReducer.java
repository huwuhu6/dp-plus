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
            case RequirementChange.RelativePreference preference -> { if (!relative.contains(preference)) relative.add(preference); }
            case RequirementChange.RelaxationAuthorization authorization -> relaxable.add(authorization.dimension());
            case RequirementChange.RequirementLock lock -> locked.add(lock.dimension());
            case RequirementChange.ConditionalRequirementChange ignored -> { /* external observation decides whether it may become durable */ }
        };
        return new V2TaskState(previous.lifecycle(), criteria, relative, relaxable, locked, previous.rejectedShopIds(),
                previous.feedbackLedger(), previous.searchAnchor(), previous.selectedShopId(), previous.recommendationBatches());
    }

    public V2TaskState reduce(V2TaskState previous, TurnSemantics turn, com.hmdp.ai.v2.grounding.GroundedTurn grounded) {
        return reduceFeedback(reduce(previous, turn), grounded);
    }

    private V2TaskState reduceFeedback(V2TaskState reduced, com.hmdp.ai.v2.grounding.GroundedTurn grounded) {
        Set<Long> rejected = new HashSet<>(reduced.rejectedShopIds());
        List<V2FeedbackEntry> ledger = new ArrayList<>(reduced.feedbackLedger());
        for (var item : grounded.feedback()) {
            if (item.feedback() instanceof EntityFeedback.EntityFeedbackItem entity) {
                for (var operand : item.operands()) if (operand instanceof com.hmdp.ai.v2.grounding.GroundedReference.ShopIdentity shop) {
                    if (entity.kind() == EntityFeedback.FeedbackKind.REJECT) rejected.add(shop.shopId());
                    ledger.add(new V2FeedbackEntry(V2FeedbackEntry.Scope.ENTITY, shop.shopId(), shop.batchId(), entity.kind(), null, entity.aspect()));
                }
            } else {
                for (var operand : item.operands()) if (operand instanceof com.hmdp.ai.v2.grounding.GroundedReference.BatchIdentity batch) {
                    EntityFeedback.BatchFeedback feedback = (EntityFeedback.BatchFeedback) item.feedback();
                    ledger.add(new V2FeedbackEntry(V2FeedbackEntry.Scope.BATCH, null, batch.batchId(), null, feedback.polarity(), feedback.aspect()));
                }
            }
        }
        return new V2TaskState(reduced.lifecycle(), reduced.criteria(), reduced.relativePreferences(), reduced.relaxable(), reduced.locked(),
                rejected, ledger, reduced.searchAnchor(), reduced.selectedShopId(), reduced.recommendationBatches());
    }
}
