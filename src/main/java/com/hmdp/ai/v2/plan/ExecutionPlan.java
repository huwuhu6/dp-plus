package com.hmdp.ai.v2.plan;

import com.hmdp.ai.v2.semantic.SemanticRelation;
import java.util.List;
import java.util.Set;

/** Flat bounded DAG IR: groups may depend on earlier groups, but plans/groups never nest or loop. */
public record ExecutionPlan(List<ExecutionGroup> groups, PlanBudget budget) {
    public ExecutionPlan {
        groups = groups == null ? List.of() : List.copyOf(groups);
        if (groups.size() > budget.maxGroups()) throw new IllegalArgumentException("plan group limit exceeded");
        if (groups.stream().mapToInt(g -> g.actions().size()).sum() > budget.maxActions()) throw new IllegalArgumentException("plan action limit exceeded");
    }
    public record PlanBudget(int maxGroups, int maxActions) { public static PlanBudget defaults() { return new PlanBudget(12, 32); } }
    public record ExecutionGroup(String groupId, Set<String> dependsOnGroups, Guard guard, List<ExecutionAction> actions) {
        public ExecutionGroup { dependsOnGroups = dependsOnGroups == null ? Set.of() : Set.copyOf(dependsOnGroups); actions = actions == null ? List.of() : List.copyOf(actions); }
    }
    public record Guard(String observedRequestId, SemanticRelation.ObservationMatcher matcher) { }
}
