package com.hmdp.ai.v2.runtime;

import com.hmdp.ai.v2.plan.ExecutionAction;
import com.hmdp.ai.v2.plan.ExecutionPlan;
import com.hmdp.ai.v2.semantic.ObservationPredicate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes a bounded static DAG. The executor is intentionally a pure read/effect
 * producer: durable state is owned exclusively by the post-execution OCC boundary.
 */
@Service
public class StaticPlanExecutor {
    public ExecutionResult execute(ExecutionPlan plan, ActionExecutor actions) {
        Map<String, GroupResult> groups = new LinkedHashMap<>();
        List<ExecutionObservation> all = new ArrayList<>();
        for (ExecutionPlan.ExecutionGroup group : plan.groups()) {
            if (!dependenciesSucceeded(group, groups)) {
                List<ExecutionObservation> skipped = group.actions().stream()
                        .map(action -> ExecutionObservation.skipped(action.requestId(), "dependency did not succeed")).toList();
                GroupResult result = new GroupResult(GroupStatus.SKIPPED, skipped);
                groups.put(group.groupId(), result); all.addAll(skipped);
                continue;
            }
            if (!guardMatches(group.guard(), groups)) {
                List<ExecutionObservation> skipped = group.actions().stream()
                        .map(action -> ExecutionObservation.skipped(action.requestId(), "guard did not match")).toList();
                GroupResult result = new GroupResult(GroupStatus.SKIPPED, skipped);
                groups.put(group.groupId(), result); all.addAll(skipped);
                continue;
            }
            List<ExecutionObservation> observations = new ArrayList<>();
            for (ExecutionAction action : group.actions()) {
                try {
                    observations.add(actions.execute(action));
                } catch (RuntimeException e) {
                    observations.add(new ExecutionObservation(action.requestId(), ExecutionObservation.Status.FAILURE,
                            List.of(), null, List.of(), null, e.getMessage()));
                }
            }
            GroupStatus status = observations.stream().anyMatch(item -> item.status() == ExecutionObservation.Status.FAILURE
                    || item.status() == ExecutionObservation.Status.TIMEOUT || item.status() == ExecutionObservation.Status.CANCELLED)
                    ? GroupStatus.FAILURE : GroupStatus.SUCCESS;
            GroupResult result = new GroupResult(status, observations);
            groups.put(group.groupId(), result); all.addAll(observations);
        }
        return new ExecutionResult(Map.copyOf(groups), List.copyOf(all));
    }

    private boolean dependenciesSucceeded(ExecutionPlan.ExecutionGroup group, Map<String, GroupResult> groups) {
        return group.dependsOnGroups().stream().allMatch(id -> groups.containsKey(id) && groups.get(id).status == GroupStatus.SUCCESS);
    }

    private boolean guardMatches(ExecutionPlan.Guard guard, Map<String, GroupResult> groups) {
        if (guard == null) return true;
        for (GroupResult group : groups.values()) for (ExecutionObservation observation : group.observations) {
            if (!guard.observedRequestId().equals(observation.requestId())) continue;
            return matches(guard.predicate(), observation);
        }
        return false;
    }

    private boolean matches(ObservationPredicate predicate, ExecutionObservation observation) {
        return switch (predicate) {
            case ObservationPredicate.ResultStateIs state -> state.expected() == ObservationPredicate.ResultState.EMPTY
                    ? observation.status() == ExecutionObservation.Status.EMPTY
                    : observation.status() == ExecutionObservation.Status.SUCCESS;
            case ObservationPredicate.BooleanEquals ignored -> false;
            case ObservationPredicate.NumericCompare ignored -> false;
            case ObservationPredicate.CategoryEquals ignored -> false;
        };
    }

    public interface ActionExecutor {
        ExecutionObservation execute(ExecutionAction action);
    }
    public record ExecutionResult(Map<String, GroupResult> groups, List<ExecutionObservation> observations) { }
    public record GroupResult(GroupStatus status, List<ExecutionObservation> observations) {
        public GroupResult { observations = List.copyOf(observations); }
    }
    public enum GroupStatus { SUCCESS, FAILURE, SKIPPED }
}
