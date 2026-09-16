package com.hmdp.ai.v2.grounding;

import com.hmdp.ai.v2.semantic.EntityReference;
import com.hmdp.ai.v2.semantic.EntityFeedback;
import com.hmdp.ai.v2.semantic.UserRequest;
import java.util.ArrayList;
import java.util.List;

public final class GroundingResolver {
    public GroundingResult resolve(com.hmdp.ai.v2.semantic.TurnSemantics semantics, EffectiveTaskContext context) {
        try { return new GroundingResult.Grounded(ground(semantics, context)); }
        catch (ReferenceIssue issue) { return new GroundingResult.NeedsClarification(List.of(new Ambiguity(issue.kind, issue.getMessage()))); }
    }
    public GroundedTurn ground(com.hmdp.ai.v2.semantic.TurnSemantics semantics, EffectiveTaskContext context) {
        List<GroundedRequest> requests = new ArrayList<>();
        for (UserRequest request : semantics.requests()) requests.add(new GroundedRequest(request, operands(request, context.task())));
        List<GroundedFeedback> feedback = new ArrayList<>();
        for (EntityFeedback item : semantics.entityFeedback()) {
            if (item instanceof EntityFeedback.EntityFeedbackItem entity)
                feedback.add(new GroundedFeedback(item, List.of(resolve(entity.target(), context.task()))));
            else feedback.add(new GroundedFeedback(item, List.of(batch(((EntityFeedback.BatchFeedback) item).target(), context.task()))));
        }
        return new GroundedTurn(semantics, context, requests, feedback);
    }
    private List<GroundedReference> operands(UserRequest request, TaskView task) {
        List<EntityReference> refs = switch (request) {
            case UserRequest.FactQueryRequest r -> List.of(r.target());
            case UserRequest.CompareRequest r -> r.targets();
            case UserRequest.SimilarRequest r -> List.of(r.anchor());
            case UserRequest.SelectRequest r -> List.of(r.target());
            case UserRequest.ExploreRequest r -> r.target() == null ? List.of() : List.of(r.target());
            default -> List.of();
        };
        List<GroundedReference> result = new ArrayList<>();
        for (EntityReference ref : refs) result.add(resolve(ref, task));
        return result;
    }
    private GroundedReference resolve(EntityReference ref, TaskView task) {
        if (ref instanceof EntityReference.TaskRef) return new GroundedReference.TaskIdentity(task.taskId());
        if (ref instanceof EntityReference.OrdinalRef ordinal) {
            // Only the projection knows which batch remains visible to a user; resolver must not infer persistence lifecycle.
            TaskView.RecommendationBatchView batch = task.currentVisibleBatch();
            if (batch == null || batch.candidates().size() < ordinal.ordinal()) throw new ReferenceIssue(Ambiguity.Kind.UNRESOLVED_REFERENCE, "ordinal reference is not in current visible batch");
            return new GroundedReference.ShopIdentity(batch.candidates().get(ordinal.ordinal() - 1).shopId(), batch.batchId(), ordinal.ordinal());
        }
        if (ref instanceof EntityReference.LastVisibleRef) {
            TaskView.RecommendationBatchView batch = task.currentVisibleBatch();
            if (batch == null || batch.candidates().isEmpty())
                throw new ReferenceIssue(Ambiguity.Kind.UNRESOLVED_REFERENCE, "last visible reference has no current visible batch");
            int ordinal = batch.candidates().size();
            return new GroundedReference.ShopIdentity(batch.candidates().get(ordinal - 1).shopId(), batch.batchId(), ordinal);
        }
        if (ref instanceof EntityReference.FocusedEntityRef && task.focusedShopId() != null)
            return new GroundedReference.ShopIdentity(task.focusedShopId(), null, 0);
        if (ref instanceof EntityReference.NamedEntityRef named) {
            TaskView.RecommendationBatchView batch = task.currentVisibleBatch();
            List<GroundedReference.ShopIdentity> matches = new ArrayList<>();
            if (batch != null) for (int i = 0; i < batch.candidates().size(); i++) {
                TaskView.ShopView candidate = batch.candidates().get(i);
                if (candidate.name() != null && candidate.name().contains(named.name()))
                    matches.add(new GroundedReference.ShopIdentity(candidate.shopId(), batch.batchId(), i + 1));
            }
            if (matches.size() == 1) return matches.getFirst();
            if (matches.size() > 1) throw new ReferenceIssue(Ambiguity.Kind.AMBIGUOUS_REFERENCE, "named entity matches multiple visible candidates");
        }
        throw new ReferenceIssue(Ambiguity.Kind.UNRESOLVED_REFERENCE, "reference is unresolved");
    }
    private GroundedReference.BatchIdentity batch(com.hmdp.ai.v2.semantic.BatchRef ref, TaskView task) {
        if (ref != null && ref.selector() == com.hmdp.ai.v2.semantic.BatchRef.BatchSelector.CURRENT_VISIBLE && task.currentVisibleBatch() != null)
            return new GroundedReference.BatchIdentity(task.currentVisibleBatch().batchId());
        throw new ReferenceIssue(Ambiguity.Kind.UNRESOLVED_REFERENCE, "current visible batch is unavailable");
    }
    private static final class ReferenceIssue extends RuntimeException {
        private final Ambiguity.Kind kind;
        private ReferenceIssue(Ambiguity.Kind kind, String message) { super(message); this.kind = kind; }
    }
}
