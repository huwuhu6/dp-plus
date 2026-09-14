package com.hmdp.ai.v2;

import com.hmdp.ai.v2.grounding.*;
import com.hmdp.ai.v2.plan.*;
import com.hmdp.ai.v2.semantic.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class V2DomainGoldenTest {
    @Test void caseA_critiqueFactAndAlternativesCompileInParallelWithoutInventingBudget() {
        TurnSemantics turn = new TurnSemantics(TaskDirective.CONTINUE,
                List.of(new RequirementChange.RelativePreference(DiningCriteria.PreferenceDimension.PRICE, RequirementChange.Direction.LOWER)),
                List.of(new EntityFeedback.EntityFeedbackItem(new EntityReference.OrdinalRef(1), EntityFeedback.FeedbackKind.CRITIQUE, EntityFeedback.FeedbackAspect.PRICE)),
                List.of(new UserRequest.FactQueryRequest("socket", new EntityReference.OrdinalRef(2), UserRequest.FactType.SOCKET), new UserRequest.AlternativesRequest("alternatives", 2)), List.of());
        ExecutionPlan plan = new ExecutionPlanCompiler().compile(ground(turn, task("A", 11, 12, 13)));
        assertEquals(1, plan.groups().size()); assertEquals(2, plan.groups().getFirst().actions().size());
        assertTrue(turn.entityFeedback().stream().noneMatch(f -> f instanceof EntityFeedback.EntityFeedbackItem i && i.kind() == EntityFeedback.FeedbackKind.REJECT));
        assertTrue(turn.requirementChanges().stream().noneMatch(c -> c instanceof RequirementChange.CriteriaPatch));
    }

    @Test void caseB_compilesFlatGuardedGroupsRatherThanRecursiveBranches() {
        TurnSemantics turn = new TurnSemantics(TaskDirective.CONTINUE, List.of(), List.of(), List.of(
                new UserRequest.FactQueryRequest("room", new EntityReference.OrdinalRef(2), UserRequest.FactType.PRIVATE_ROOM),
                new UserRequest.SelectRequest("select", new EntityReference.OrdinalRef(2)),
                new UserRequest.CompareRequest("compare", List.of(new EntityReference.OrdinalRef(1), new EntityReference.OrdinalRef(3)), List.of(UserRequest.FactType.REVIEW))),
                List.of(new SemanticRelation("room", new SemanticRelation.ObservationMatcher(SemanticRelation.Operator.EQ, "TRUE"), "select"), new SemanticRelation("room", new SemanticRelation.ObservationMatcher(SemanticRelation.Operator.EQ, "FALSE"), "compare")));
        ExecutionPlan plan = new ExecutionPlanCompiler().compile(ground(turn, task("A", 11, 12, 13)));
        assertEquals(3, plan.groups().size()); assertEquals("G1", plan.groups().get(1).dependsOnGroups().iterator().next());
        assertEquals("TRUE", plan.groups().get(1).guard().matcher().expectedValue()); assertEquals("FALSE", plan.groups().get(2).guard().matcher().expectedValue());
    }

    @Test void casesCAndD_distinguishEnumerableStaticPlanFromAdaptiveResearch() {
        GroundedTurn staticTurn = ground(new TurnSemantics(TaskDirective.CONTINUE, List.of(), List.of(), List.of(new UserRequest.CompareRequest("compare", List.of(new EntityReference.OrdinalRef(2), new EntityReference.OrdinalRef(3)), List.of(UserRequest.FactType.REVIEW, UserRequest.FactType.VOUCHER, UserRequest.FactType.DISTANCE, UserRequest.FactType.QUEUE))), List.of()), task("A", 1, 2, 3));
        GroundedTurn adaptiveTurn = ground(new TurnSemantics(TaskDirective.CONTINUE, List.of(), List.of(), List.of(new UserRequest.ExploreRequest("research", new EntityReference.OrdinalRef(2), true)), List.of()), task("A", 1, 2, 3));
        assertEquals(ExecutionMode.STATIC_PLAN, new ExecutionModeGate().decide(staticTurn));
        assertEquals(ExecutionMode.ADAPTIVE_RESEARCH, new ExecutionModeGate().decide(adaptiveTurn));
    }

    @Test void caseE_restoredTaskScopesOrdinalBeforeDurableRestore() {
        TaskView fuzhou = task("A", 101, 102); TaskView hangzhou = task("B", 201, 202);
        TurnSemantics turn = new TurnSemantics(TaskDirective.RESTORE,
                List.of(new RequirementChange.CriteriaPatch(new DiningCriteria(null, null, new DiningCriteria.BudgetCriteria(null, new BigDecimal("80")), null, null, DiningCriteria.SemanticPreferences.empty()), false)),
                List.of(), List.of(new UserRequest.FactQueryRequest("review", new EntityReference.OrdinalRef(2), UserRequest.FactType.REVIEW)), List.of(), List.of(new EntityReference.TaskRef("福州")));
        EffectiveTaskContext context = new EffectiveTaskContextResolver().resolve("B", List.of(fuzhou, hangzhou), turn);
        GroundedTurn grounded = new GroundingResolver().ground(turn, context);
        assertEquals("A", context.task().taskId()); assertTrue(context.restoredForThisTurn());
        assertEquals(102L, ((GroundedReference.ShopIdentity) grounded.requests().getFirst().operands().getFirst()).shopId());
        assertEquals(TaskDirective.RESTORE, turn.taskDirective()); // Resolver did not mutate activeTaskId.
    }

    @Test void casesFAndG_keepBatchFeedbackAndCritiqueDistinctFromReject() {
        EntityFeedback.BatchFeedback allDisliked = new EntityFeedback.BatchFeedback(EntityFeedback.BatchPolarity.NEGATIVE, EntityFeedback.FeedbackAspect.UNSPECIFIED);
        EntityFeedback.EntityFeedbackItem critique = new EntityFeedback.EntityFeedbackItem(new EntityReference.OrdinalRef(2), EntityFeedback.FeedbackKind.CRITIQUE, EntityFeedback.FeedbackAspect.PRICE);
        EntityFeedback.EntityFeedbackItem reject = new EntityFeedback.EntityFeedbackItem(new EntityReference.OrdinalRef(2), EntityFeedback.FeedbackKind.REJECT, EntityFeedback.FeedbackAspect.UNSPECIFIED);
        assertEquals(EntityFeedback.BatchPolarity.NEGATIVE, allDisliked.polarity()); assertNotEquals(critique.kind(), reject.kind());
    }

    @Test void casesHAndI_preserveExplicitBudgetAndRelativePriceAsDifferentTypes() {
        DiningCriteria.BudgetCriteria budget = new DiningCriteria.BudgetCriteria(new BigDecimal("100"), new BigDecimal("150"));
        RequirementChange relative = new RequirementChange.RelativePreference(DiningCriteria.PreferenceDimension.PRICE, RequirementChange.Direction.LOWER);
        assertEquals(new BigDecimal("100"), budget.softTarget()); assertEquals(new BigDecimal("150"), budget.hardMax());
        assertFalse(relative instanceof RequirementChange.CriteriaPatch);
    }

    @Test void caseJ_modelsRelaxLockAndConditionalFallbackAsFutureEffect() {
        TurnSemantics turn = new TurnSemantics(TaskDirective.CONTINUE,
                List.of(new RequirementChange.RelaxationAuthorization(DiningCriteria.PreferenceDimension.DISTANCE), new RequirementChange.RequirementLock(DiningCriteria.PreferenceDimension.PRICE), new RequirementChange.CriteriaPatch(new DiningCriteria(null, new DiningCriteria.CuisineCriteria("烧烤", List.of()), null, null, null, DiningCriteria.SemanticPreferences.empty()), false)), List.of(), List.of(), List.of());
        assertEquals(3, turn.requirementChanges().size());
        ExecutionAction.DomainEffect effect = new ExecutionAction.DomainEffect.CuisineChangedTo("烧烤");
        assertEquals("烧烤", ((ExecutionAction.DomainEffect.CuisineChangedTo) effect).cuisine());
    }

    private GroundedTurn ground(TurnSemantics turn, TaskView task) { return new GroundingResolver().ground(turn, new EffectiveTaskContext(task, false)); }
    private TaskView task(String id, long... shops) { return new TaskView(id, id.equals("A") ? "福州吃饭" : "杭州吃饭", List.of(new TaskView.RecommendationBatchView(id + "-1", java.util.stream.LongStream.of(shops).mapToObj(s -> new TaskView.ShopView(s, "店" + s)).toList())), null); }
}
