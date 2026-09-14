package com.hmdp.ai.v2.plan;

import com.hmdp.ai.v2.grounding.GroundedReference;
import com.hmdp.ai.v2.grounding.GroundedRequest;
import com.hmdp.ai.v2.grounding.GroundedTurn;
import com.hmdp.ai.v2.semantic.SemanticRelation;
import com.hmdp.ai.v2.semantic.UserRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Pure compiler. It has no tool client, LLM client, database identity lookup, or state writer. */
public final class ExecutionPlanCompiler {
    public ExecutionPlan compile(GroundedTurn turn) {
        if (new ExecutionModeGate().decide(turn) == ExecutionMode.ADAPTIVE_RESEARCH)
            return new ExecutionPlan(List.of(), ExecutionPlan.PlanBudget.defaults());
        Map<String, SemanticRelation> guarded = turn.semantics().relations().stream()
                .collect(Collectors.toMap(SemanticRelation::dependentRequestId, r -> r, (a, b) -> { throw new IllegalArgumentException("one request may have one guard"); }));
        List<ExecutionAction> rootActions = new ArrayList<>(); List<ExecutionPlan.ExecutionGroup> groups = new ArrayList<>(); int branch = 2;
        for (GroundedRequest request : turn.requests()) {
            ExecutionAction action = action(request);
            SemanticRelation relation = guarded.get(request.request().requestId());
            if (relation == null) rootActions.add(action);
            else groups.add(new ExecutionPlan.ExecutionGroup("G" + branch++, SetBuilder.of("G1"),
                    new ExecutionPlan.Guard(relation.observedRequestId(), relation.matcher()), List.of(action)));
        }
        if (!rootActions.isEmpty()) groups.add(0, new ExecutionPlan.ExecutionGroup("G1", new LinkedHashSet<>(), null, rootActions));
        validateRelations(turn, groups);
        return new ExecutionPlan(groups, ExecutionPlan.PlanBudget.defaults());
    }
    private ExecutionAction action(GroundedRequest grounded) {
        UserRequest r = grounded.request();
        return switch (r) {
            case UserRequest.FactQueryRequest fact -> new ExecutionAction.ToolAction(fact.requestId(), shop(grounded), fact.fact());
            case UserRequest.AlternativesRequest alternatives -> new ExecutionAction.SearchAction(alternatives.requestId(), ExecutionAction.SearchKind.ALTERNATIVES, alternatives.count());
            case UserRequest.RecommendationRequest recommendation -> new ExecutionAction.SearchAction(recommendation.requestId(), ExecutionAction.SearchKind.RECOMMENDATIONS, 0);
            case UserRequest.SimilarRequest similar -> new ExecutionAction.SearchAction(similar.requestId(), ExecutionAction.SearchKind.SIMILAR, 0);
            case UserRequest.CompareRequest compare -> new ExecutionAction.EvaluateAction(compare.requestId(), shops(grounded), compare.dimensions());
            case UserRequest.SelectRequest select -> new ExecutionAction.EmitDomainEffectAction(select.requestId(), new ExecutionAction.DomainEffect.CandidateSelected(shop(grounded).shopId()));
            case UserRequest.ExploreRequest explore -> new ExecutionAction.ToolAction(explore.requestId(), shop(grounded), UserRequest.FactType.EVIDENCE);
            case UserRequest.GeneralKnowledgeRequest general -> new ExecutionAction.SearchAction(general.requestId(), ExecutionAction.SearchKind.RECOMMENDATIONS, 0);
        };
    }
    private GroundedReference.ShopIdentity shop(GroundedRequest request) { return request.operands().stream().filter(GroundedReference.ShopIdentity.class::isInstance).map(GroundedReference.ShopIdentity.class::cast).findFirst().orElseThrow(() -> new IllegalArgumentException("request needs grounded shop identity")); }
    private List<GroundedReference.ShopIdentity> shops(GroundedRequest request) { return request.operands().stream().filter(GroundedReference.ShopIdentity.class::isInstance).map(GroundedReference.ShopIdentity.class::cast).toList(); }
    private void validateRelations(GroundedTurn turn, List<ExecutionPlan.ExecutionGroup> groups) {
        List<String> order = turn.requests().stream().map(r -> r.request().requestId()).toList();
        for (SemanticRelation relation : turn.semantics().relations()) {
            if (order.indexOf(relation.observedRequestId()) < 0 || order.indexOf(relation.observedRequestId()) >= order.indexOf(relation.dependentRequestId()))
                throw new IllegalArgumentException("guard can only observe a preceding request");
        }
    }
    private static final class SetBuilder { static java.util.Set<String> of(String value) { return java.util.Set.of(value); } }
}
