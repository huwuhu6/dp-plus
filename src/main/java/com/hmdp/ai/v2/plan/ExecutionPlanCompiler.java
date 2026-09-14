package com.hmdp.ai.v2.plan;

import com.hmdp.ai.v2.grounding.GroundedReference;
import com.hmdp.ai.v2.grounding.GroundedRequest;
import com.hmdp.ai.v2.grounding.GroundedTurn;
import com.hmdp.ai.v2.semantic.SemanticRelation;
import com.hmdp.ai.v2.semantic.UserRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure compilation from a committed snapshot. Groups are a bounded DAG, never a recursive workflow. */
public final class ExecutionPlanCompiler {
    public CompilationResult compile(GroundedTurn turn, PlanningSnapshot snapshot) {
        ExecutionMode mode = new ExecutionModeGate().decide(turn);
        if (mode == ExecutionMode.ADAPTIVE_RESEARCH) {
            List<GroundedReference.ShopIdentity> whitelist = turn.requests().stream()
                    .filter(r -> r.request() instanceof UserRequest.ExploreRequest)
                    .flatMap(r -> r.operands().stream()).filter(GroundedReference.ShopIdentity.class::isInstance)
                    .map(GroundedReference.ShopIdentity.class::cast).toList();
            return new CompilationResult.Adaptive(new AdaptiveResearchContract(whitelist, List.of(UserRequest.FactType.EVIDENCE), 4, 4, Duration.ofSeconds(12), true, true));
        }
        if (mode == ExecutionMode.DIRECT) return new CompilationResult.Direct(action(turn.requests().getFirst(), snapshot));
        Map<String, SemanticRelation> guarded = new HashMap<>();
        for (SemanticRelation relation : turn.semantics().relations()) {
            if (guarded.put(relation.dependentRequestId(), relation) != null) throw new IllegalArgumentException("one request may have one guard");
        }
        List<ExecutionPlan.ExecutionGroup> groups = new ArrayList<>();
        Map<String, String> producerGroups = new HashMap<>();
        int groupNumber = 1;
        for (GroundedRequest request : turn.requests()) {
            SemanticRelation relation = guarded.get(request.request().requestId());
            String groupId = "G" + groupNumber++;
            Set<String> dependencies = Set.of(); ExecutionPlan.Guard guard = null;
            if (relation != null) {
                String producingGroup = producerGroups.get(relation.observedRequestId());
                if (producingGroup == null) throw new IllegalArgumentException("guard must observe an already produced request");
                dependencies = Set.of(producingGroup); guard = new ExecutionPlan.Guard(relation.observedRequestId(), relation.predicate());
            }
            groups.add(new ExecutionPlan.ExecutionGroup(groupId, dependencies, guard, List.of(action(request, snapshot))));
            producerGroups.put(request.request().requestId(), groupId);
        }
        for (com.hmdp.ai.v2.semantic.RequirementChange change : turn.semantics().requirementChanges()) {
            if (!(change instanceof com.hmdp.ai.v2.semantic.RequirementChange.ConditionalRequirementChange conditional)) continue;
            String producer = producerGroups.get(conditional.observedRequestId());
            if (producer == null) throw new IllegalArgumentException("conditional change must observe a producing request");
            SearchSpec fallback = conditionalSearchSpec(snapshot, conditional.change());
            List<ExecutionAction> actions = new ArrayList<>();
            actions.add(new ExecutionAction.SearchAction("fallback-" + conditional.observedRequestId(), fallback));
            String fallbackGroup = "G" + groupNumber++;
            groups.add(new ExecutionPlan.ExecutionGroup(fallbackGroup, Set.of(producer), new ExecutionPlan.Guard(conditional.observedRequestId(), conditional.predicate()), List.of(new ExecutionAction.SearchAction("fallback-" + conditional.observedRequestId(), fallback))));
            // EMPTY is a successful observation; only technical group failure skips this dependent effect at execution time.
            groups.add(new ExecutionPlan.ExecutionGroup("G" + groupNumber++, Set.of(fallbackGroup), null, List.of(new ExecutionAction.EmitDomainEffectAction("effect-" + conditional.observedRequestId(), new ExecutionAction.DomainEffect.ConditionalCriteriaApplied(conditional.change())))));
        }
        ExecutionPlan plan = new ExecutionPlan(groups, ExecutionPlan.PlanBudget.defaults());
        return new CompilationResult.StaticPlan(plan);
    }
    private ExecutionAction action(GroundedRequest grounded, PlanningSnapshot snapshot) {
        UserRequest request = grounded.request();
        return switch (request) {
            case UserRequest.FactQueryRequest fact -> new ExecutionAction.ToolAction(fact.requestId(), shop(grounded), fact.fact());
            case UserRequest.AlternativesRequest alternatives -> search(alternatives.requestId(), ExecutionAction.SearchKind.ALTERNATIVES, alternatives.count(), grounded, snapshot);
            case UserRequest.RecommendationRequest recommendation -> search(recommendation.requestId(), ExecutionAction.SearchKind.RECOMMENDATIONS, 0, grounded, snapshot);
            case UserRequest.SimilarRequest similar -> search(similar.requestId(), ExecutionAction.SearchKind.SIMILAR, 0, grounded, snapshot);
            case UserRequest.CompareRequest compare -> new ExecutionAction.CompareAction(compare.requestId(), shops(grounded), compare.dimensions());
            case UserRequest.SelectRequest select -> new ExecutionAction.EmitDomainEffectAction(select.requestId(), new ExecutionAction.DomainEffect.CandidateSelected(shop(grounded).shopId()));
            case UserRequest.ExploreRequest explore -> new ExecutionAction.ToolAction(explore.requestId(), shop(grounded), UserRequest.FactType.EVIDENCE);
            case UserRequest.GeneralKnowledgeRequest general -> new ExecutionAction.GeneralAnswerAction(general.requestId(), general.topic());
        };
    }
    private ExecutionAction.SearchAction search(String requestId, ExecutionAction.SearchKind kind, int count, GroundedRequest request, PlanningSnapshot snapshot) {
        GroundedReference.ShopIdentity anchor = request.operands().stream().filter(GroundedReference.ShopIdentity.class::isInstance).map(GroundedReference.ShopIdentity.class::cast).findFirst().orElse(null);
        Set<Long> excluded = new java.util.HashSet<>(snapshot.rejectedShopIds());
        if (kind == ExecutionAction.SearchKind.ALTERNATIVES) excluded.addAll(snapshot.currentVisibleShopIds());
        return new ExecutionAction.SearchAction(requestId, new SearchSpec(snapshot.baseMemoryVersion(), snapshot.taskId(), kind, count, snapshot.criteria(), snapshot.relativePreferences(), excluded, anchor, snapshot.searchAnchor()));
    }
    private SearchSpec conditionalSearchSpec(PlanningSnapshot snapshot, com.hmdp.ai.v2.semantic.RequirementChange.CriteriaPatch patch) {
        var criteria = com.hmdp.ai.v2.semantic.CriteriaPatchApplier.apply(snapshot.criteria(), patch);
        return new SearchSpec(snapshot.baseMemoryVersion(), snapshot.taskId(), ExecutionAction.SearchKind.RECOMMENDATIONS, 0, criteria, snapshot.relativePreferences(), snapshot.rejectedShopIds(), null, snapshot.searchAnchor());
    }
    private GroundedReference.ShopIdentity shop(GroundedRequest request) { return request.operands().stream().filter(GroundedReference.ShopIdentity.class::isInstance).map(GroundedReference.ShopIdentity.class::cast).findFirst().orElseThrow(() -> new IllegalArgumentException("request needs grounded shop identity")); }
    private List<GroundedReference.ShopIdentity> shops(GroundedRequest request) { return request.operands().stream().filter(GroundedReference.ShopIdentity.class::isInstance).map(GroundedReference.ShopIdentity.class::cast).toList(); }
}
