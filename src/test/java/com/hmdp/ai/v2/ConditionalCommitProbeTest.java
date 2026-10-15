package com.hmdp.ai.v2;

import com.hmdp.ai.v2.grounding.*;
import com.hmdp.ai.v2.plan.*;
import com.hmdp.ai.v2.reducer.*;
import com.hmdp.ai.v2.runtime.*;
import com.hmdp.ai.v2.semantic.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ConditionalCommitProbeTest {
    private static final String R = "primary";
    private static final DiningCriteria BASE = new DiningCriteria(
            null, new DiningCriteria.CuisineCriteria("藏式火锅", List.of()),
            new DiningCriteria.BudgetCriteria(null, BigDecimal.valueOf(150)),
            new DiningCriteria.DistanceCriteria(BigDecimal.valueOf(3)), null,
            DiningCriteria.SemanticPreferences.empty());

    @Test void c01FallbackCuisineEmptyMustNotOverwritePrimary() {
        probe("C01 cuisine", change(new DiningCriteriaPatch(null, new DiningCriteria.CuisineCriteria("烧烤", List.of()),null,null,null,null), Set.of()));
    }
    @Test void c02FallbackBudgetEmptyMustNotOverwriteHardBudget() {
        probe("C02 budget", change(new DiningCriteriaPatch(null,null,new DiningCriteria.BudgetCriteria(null,BigDecimal.valueOf(100)),null,null,null), Set.of()));
    }
    @Test void c03FallbackRadiusEmptyMustNotOverwriteHardRadius() {
        probe("C03 radius", change(new DiningCriteriaPatch(null,null,null,new DiningCriteria.DistanceCriteria(BigDecimal.valueOf(10)),null,null), Set.of()));
    }
    @Test void c04FallbackEmptyMustNotClearExistingBudget() {
        probe("C04 clear", change(new DiningCriteriaPatch(null,null,null,null,null,null),Set.of(RequirementChange.ClearedCriterion.BUDGET)));
    }
    @Test void c05FallbackMultipleFieldsEmptyMustNotPolluteAnyField() {
        probe("C05 multi", change(new DiningCriteriaPatch(null,new DiningCriteria.CuisineCriteria("烧烤",List.of()),new DiningCriteria.BudgetCriteria(null,BigDecimal.valueOf(90)),null,null,null),Set.of()));
    }

    private RequirementChange.CriteriaPatch change(DiningCriteriaPatch patch, Set<RequirementChange.ClearedCriterion> cleared) {
        return new RequirementChange.CriteriaPatch(patch, cleared);
    }

    private void probe(String label, RequirementChange.CriteriaPatch patch) {
        V2TaskState original = new V2TaskState(BASE, List.of(), Set.of(), Set.of());
        TurnSemantics turn = new TurnSemantics(TaskDirective.CONTINUE,
                List.of(new RequirementChange.ConditionalRequirementChange(R,
                        new ObservationPredicate.ResultStateIs(ObservationPredicate.ResultState.EMPTY), patch)),
                List.of(), List.of(new UserRequest.RecommendationRequest(R)), List.of());
        V2TaskState pre = new PreExecutionReducer().reduce(original, turn);
        assertEquals(BASE, pre.criteria(), label + " pre must not prematurely commit fallback");

        TaskView task = new TaskView("task", 1, "DINING", "福州", null, null);
        GroundedTurn grounded = new GroundingResolver().ground(turn, new EffectiveTaskContext(task,false));
        PlanningSnapshot snapshot = new PlanningSnapshot(1, "task", pre.criteria(), List.of(), Set.of(), Set.of(), Set.of(), null);
        ExecutionPlan plan = ((CompilationResult.StaticPlan) new ExecutionPlanCompiler().compile(grounded,snapshot)).plan();
        StaticPlanExecutor.ExecutionResult result = new StaticPlanExecutor().execute(plan, action -> {
            if (action instanceof ExecutionAction.SearchAction)
                return new ExecutionObservation(action.requestId(), ExecutionObservation.Status.EMPTY, List.of(), null, List.of(), null, null);
            if (action instanceof ExecutionAction.EmitDomainEffectAction effect)
                return new ExecutionObservation(action.requestId(), ExecutionObservation.Status.SUCCESS, List.of(), null, List.of(effect.effect()), null, null);
            throw new IllegalArgumentException("unexpected action: "+action);
        });
        assertEquals(3, result.observations().size(), label + " all three DAG groups must run");
        assertEquals(ExecutionObservation.Status.EMPTY, result.observations().get(0).status());
        assertEquals(ExecutionObservation.Status.EMPTY, result.observations().get(1).status());

        V2TaskState post = new PostExecutionReducer().apply(pre,result);
        System.out.printf("COND_PROBE %s pre=%s root=%s fallback=%s durable=%s%n",label,
                pre.criteria(),result.observations().get(0).status(),result.observations().get(1).status(),post.criteria());
        assertEquals(BASE, post.criteria(), label + " no executable result -> fallback cannot become durable");
    }
}
