package com.hmdp.ai.v2.grounding;

import com.hmdp.ai.v2.semantic.EntityReference;
import com.hmdp.ai.v2.semantic.EntityFeedback;
import com.hmdp.ai.v2.semantic.UserRequest;
import java.util.ArrayList;
import java.util.List;

public final class GroundingResolver {
    public GroundingResult resolve(com.hmdp.ai.v2.semantic.TurnSemantics semantics, EffectiveTaskContext context) {
        return resolve(semantics, context, "");
    }
    public GroundingResult resolve(com.hmdp.ai.v2.semantic.TurnSemantics semantics, EffectiveTaskContext context, String userTurn) {
        try { return new GroundingResult.Grounded(ground(semantics, context, userTurn)); }
        catch (ReferenceIssue issue) { return new GroundingResult.NeedsClarification(List.of(new Ambiguity(issue.kind, issue.getMessage()))); }
    }
    public GroundedTurn ground(com.hmdp.ai.v2.semantic.TurnSemantics semantics, EffectiveTaskContext context) {
        return ground(semantics, context, "");
    }
    public GroundedTurn ground(com.hmdp.ai.v2.semantic.TurnSemantics semantics, EffectiveTaskContext context, String userTurn) {
        List<GroundedRequest> requests = new ArrayList<>();
        for (UserRequest request : semantics.requests()) requests.add(new GroundedRequest(request, operands(request, context.task(), userTurn)));
        List<GroundedFeedback> feedback = new ArrayList<>();
        for (EntityFeedback item : semantics.entityFeedback()) {
            if (item instanceof EntityFeedback.EntityFeedbackItem entity)
                feedback.add(new GroundedFeedback(item, List.of(resolve(entity.target(), context.task(), userTurn))));
            else feedback.add(new GroundedFeedback(item, List.of(batch(((EntityFeedback.BatchFeedback) item).target(), context.task()))));
        }
        return new GroundedTurn(semantics, context, requests, feedback);
    }
    private List<GroundedReference> operands(UserRequest request, TaskView task, String userTurn) {
        List<EntityReference> refs = switch (request) {
            case UserRequest.FactQueryRequest r -> List.of(r.target());
            case UserRequest.CompareRequest r -> r.targets();
            case UserRequest.SimilarRequest r -> List.of(r.anchor());
            case UserRequest.SelectRequest r -> List.of(r.target());
            case UserRequest.ExploreRequest r -> r.target() == null ? List.of() : List.of(r.target());
            default -> List.of();
        };
        List<GroundedReference> result = new ArrayList<>();
        for (EntityReference ref : refs) result.add(resolve(ref, task, userTurn));
        return result;
    }
    private GroundedReference resolve(EntityReference ref, TaskView task, String userTurn) {
        if (task == null) throw new ReferenceIssue(Ambiguity.Kind.UNRESOLVED_REFERENCE, "reference has no task context");
        if (ref instanceof EntityReference.NamedOrdinalRef joint) {
            GroundedReference.ShopIdentity named = (GroundedReference.ShopIdentity) resolve(new EntityReference.NamedEntityRef(joint.name()), task, userTurn);
            GroundedReference.ShopIdentity ordinal = (GroundedReference.ShopIdentity) resolve(new EntityReference.OrdinalRef(joint.ordinal()), task, userTurn);
            if (named.shopId()!=ordinal.shopId() || named.ordinal()!=joint.ordinal())
                throw new ReferenceIssue(Ambiguity.Kind.AMBIGUOUS_REFERENCE,"semantic name and ordinal conflict");
            return named;
        }
        GroundedReference evidence = evidencedReference(ref, task, userTurn);
        if (evidence != null) return evidence;
        if (ref instanceof EntityReference.TaskRef)
            throw new ReferenceIssue(Ambiguity.Kind.UNRESOLVED_REFERENCE,"task identity is not a merchant identity");
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
            List<GroundedReference.ShopIdentity> matches = new ArrayList<>();
            for (TaskView.RecommendationBatchView batch : task.recommendationBatches()) for (int i = 0; i < batch.candidates().size(); i++) {
                TaskView.ShopView candidate = batch.candidates().get(i);
                if (candidate.matchesDescription(named.name()))
                    matches.add(new GroundedReference.ShopIdentity(candidate.shopId(), batch.batchId(), i + 1));
            }
            if (matches.stream().map(GroundedReference.ShopIdentity::shopId).distinct().count() == 1) return matches.getLast();
            if (!matches.isEmpty()) throw new ReferenceIssue(Ambiguity.Kind.AMBIGUOUS_REFERENCE, "named entity matches multiple historical identities");
        }
        throw new ReferenceIssue(Ambiguity.Kind.UNRESOLVED_REFERENCE, "reference is unresolved");
    }
    /** 用户原文与已展示商户是两份独立证据。历史名称不能被模型的当前序号覆盖；多目标不猜关联。 */
    private GroundedReference evidencedReference(EntityReference ref, TaskView task, String text) {
        if (text == null || text.isBlank()) return null;
        int statedOrdinal = statedOrdinal(text);
        if (ref instanceof EntityReference.OrdinalRef ordinal && statedOrdinal>0 && ordinal.ordinal()!=statedOrdinal)
            throw new ReferenceIssue(Ambiguity.Kind.AMBIGUOUS_REFERENCE,"model ordinal conflicts with user ordinal");
        boolean earliest = text.matches("(?s).*(?:最开始|最初|最早|第一批|首次).*" );
        boolean previous = text.matches("(?s).*(?:上一批|前一批).*" );
        boolean historical = earliest || previous || text.matches("(?s).*(?:之前|先前|以前|上次|上回|上一轮|前一轮|昨天|前天|历史).*(?:推荐|那家|第|商户|店).*" );
        List<TaskView.RecommendationBatchView> batches = task.recommendationBatches();
        if (earliest) batches = batches.isEmpty() ? List.of() : List.of(batches.getFirst());
        if (previous) batches = batches.size() < 2 ? List.of() : List.of(batches.get(batches.size()-2));
        if (!historical && statedOrdinal>0)
            batches = task.currentVisibleBatch()==null ? List.of() : List.of(task.currentVisibleBatch());
        List<GroundedReference.ShopIdentity> mentioned = new ArrayList<>();
        for (var batch : batches) for (int i=0; i<batch.candidates().size(); i++) {
            var shop=batch.candidates().get(i);
            if (shop.name()!=null && !shop.name().isBlank() && text.contains(shop.name())
                    || ref instanceof EntityReference.NamedEntityRef named && text.contains(named.name()) && shop.matchesDescription(named.name()))
                mentioned.add(new GroundedReference.ShopIdentity(shop.shopId(),batch.batchId(),i+1));
        }
        long identities=mentioned.stream().map(GroundedReference.ShopIdentity::shopId).distinct().count();
        if ((historical || statedOrdinal>0) && identities==0 && task.recommendationBatches().stream().flatMap(b->b.candidates().stream())
                .anyMatch(s->s.name()!=null && !s.name().isBlank() && text.contains(s.name())))
            throw new ReferenceIssue(Ambiguity.Kind.AMBIGUOUS_REFERENCE,"user name is outside the referenced historical batch");
        if (identities>1) throw new ReferenceIssue(Ambiguity.Kind.AMBIGUOUS_REFERENCE,"user names multiple merchant identities; clarify target association");
        if (identities==1) {
            var match=mentioned.getLast();
            if (ref instanceof EntityReference.NamedEntityRef named && batches.stream().flatMap(b->b.candidates().stream())
                    .noneMatch(s->s.shopId()==match.shopId() && s.matchesDescription(named.name())))
                throw new ReferenceIssue(Ambiguity.Kind.AMBIGUOUS_REFERENCE,"model name conflicts with user-named merchant");
            if (statedOrdinal>0) {
                var batch=historical ? batches.isEmpty()?null:batches.getLast() : task.currentVisibleBatch();
                if (batch==null || batch.candidates().size()<statedOrdinal || batch.candidates().get(statedOrdinal-1).shopId()!=match.shopId())
                    throw new ReferenceIssue(Ambiguity.Kind.AMBIGUOUS_REFERENCE,"name and ordinal identify different merchants");
            }
            return match;
        }
        if (historical) {
            if (ref instanceof EntityReference.OrdinalRef ordinal && explicitOrdinal(text) && (earliest || previous)
                    && !batches.isEmpty() && batches.getFirst().candidates().size()>=ordinal.ordinal()) {
                var batch=batches.getFirst();
                return new GroundedReference.ShopIdentity(batch.candidates().get(ordinal.ordinal()-1).shopId(),batch.batchId(),ordinal.ordinal());
            }
            throw new ReferenceIssue(Ambiguity.Kind.UNRESOLVED_REFERENCE,"historical merchant is not uniquely evidenced");
        }
        if (ref instanceof EntityReference.OrdinalRef && !explicitOrdinal(text))
            throw new ReferenceIssue(Ambiguity.Kind.UNRESOLVED_REFERENCE,"model ordinal has no user ordinal or merchant-name evidence");
        if (ref instanceof EntityReference.NamedEntityRef named && !text.contains(named.name()))
            throw new ReferenceIssue(Ambiguity.Kind.UNRESOLVED_REFERENCE,"model merchant name has no user evidence");
        return null;
    }
    private boolean explicitOrdinal(String text) { return text.matches("(?s).*第[一二三四五六七八九十0-9]+(?:家|个|间).*" ); }
    private int statedOrdinal(String text) {
        var matcher = java.util.regex.Pattern.compile("第([一二三四五六七八九十]|[0-9]+)(?:家|个|间)").matcher(text);
        java.util.Set<Integer> values = new java.util.HashSet<>();
        while (matcher.find()) {
            String value=matcher.group(1);
            values.add(value.matches("[0-9]+") ? Integer.parseInt(value) : "一二三四五六七八九十".indexOf(value)+1);
        }
        return values.size()==1 ? values.iterator().next() : 0;
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
