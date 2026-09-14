package com.hmdp.ai.v2;

import com.hmdp.ai.v2.plan.ExecutionAction;
import com.hmdp.ai.v2.plan.ExecutionPlan;
import com.hmdp.ai.v2.runtime.ExecutionObservation;
import com.hmdp.ai.v2.runtime.StaticPlanExecutor;
import com.hmdp.ai.v2.semantic.ObservationPredicate;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StaticPlanExecutorTest {
    @Test
    void emptyDependencyIsSuccessAndEnablesConditionalEffect() {
        ExecutionPlan plan = new ExecutionPlan(List.of(
                new ExecutionPlan.ExecutionGroup("g1", Set.of(), null, List.of(new ExecutionAction.GeneralAnswerAction("r1", "x"))),
                new ExecutionPlan.ExecutionGroup("g2", Set.of("g1"), new ExecutionPlan.Guard("r1",
                        new ObservationPredicate.ResultStateIs(ObservationPredicate.ResultState.EMPTY)),
                        List.of(new ExecutionAction.GeneralAnswerAction("r2", "y")))),
                ExecutionPlan.PlanBudget.defaults());
        StaticPlanExecutor.ExecutionResult result = new StaticPlanExecutor().execute(plan, action ->
                new ExecutionObservation(action.requestId(), ExecutionObservation.Status.EMPTY, List.of(), null, List.of(), null, null));
        assertEquals(StaticPlanExecutor.GroupStatus.SUCCESS, result.groups().get("g2").status());
    }
}
