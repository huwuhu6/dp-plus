package com.hmdp.ai.v2.grounding;

import com.hmdp.ai.v2.semantic.EntityReference;
import com.hmdp.ai.v2.semantic.EntityFeedback;
import com.hmdp.ai.v2.semantic.UserRequest;
import java.util.ArrayList;
import java.util.List;

public final class GroundingResolver {
    public GroundedTurn ground(com.hmdp.ai.v2.semantic.TurnSemantics semantics, EffectiveTaskContext context) {
        List<GroundedRequest> requests = new ArrayList<>();
        for (UserRequest request : semantics.requests()) requests.add(new GroundedRequest(request, operands(request, context.task())));
        List<GroundedFeedback> feedback = new ArrayList<>();
        for (EntityFeedback item : semantics.entityFeedback()) {
            if (item instanceof EntityFeedback.EntityFeedbackItem entity)
                feedback.add(new GroundedFeedback(item, List.of(resolve(entity.target(), context.task()))));
            else feedback.add(new GroundedFeedback(item, List.of()));
        }
        return new GroundedTurn(semantics, context, requests, feedback);
    }
    private List<GroundedReference> operands(UserRequest request, TaskView task) {
        List<EntityReference> refs = switch (request) {
            case UserRequest.FactQueryRequest r -> List.of(r.target());
            case UserRequest.CompareRequest r -> r.targets();
            case UserRequest.SimilarRequest r -> List.of(r.anchor());
            case UserRequest.SelectRequest r -> List.of(r.target());
            case UserRequest.ExploreRequest r -> List.of(r.target());
            default -> List.of();
        };
        List<GroundedReference> result = new ArrayList<>();
        for (EntityReference ref : refs) result.add(resolve(ref, task));
        return result;
    }
    private GroundedReference resolve(EntityReference ref, TaskView task) {
        if (ref instanceof EntityReference.TaskRef) return new GroundedReference.TaskIdentity(task.taskId());
        if (ref instanceof EntityReference.OrdinalRef ordinal) {
            TaskView.RecommendationBatchView batch = task.batches().isEmpty() ? null : task.batches().get(task.batches().size() - 1);
            if (batch == null || batch.candidates().size() < ordinal.ordinal()) throw new IllegalArgumentException("ordinal reference is unresolved");
            return new GroundedReference.ShopIdentity(batch.candidates().get(ordinal.ordinal() - 1).shopId(), batch.batchId(), ordinal.ordinal());
        }
        if (ref instanceof EntityReference.FocusedEntityRef && task.focusedShopId() != null)
            return new GroundedReference.ShopIdentity(task.focusedShopId(), null, 0);
        if (ref instanceof EntityReference.NamedEntityRef named) {
            for (TaskView.RecommendationBatchView batch : task.batches()) for (int i = 0; i < batch.candidates().size(); i++) {
                TaskView.ShopView candidate = batch.candidates().get(i);
                if (candidate.name() != null && candidate.name().contains(named.name()))
                    return new GroundedReference.ShopIdentity(candidate.shopId(), batch.batchId(), i + 1);
            }
        }
        throw new IllegalArgumentException("reference is unresolved: " + ref);
    }
}
