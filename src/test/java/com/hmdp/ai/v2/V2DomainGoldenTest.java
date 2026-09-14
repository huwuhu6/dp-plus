package com.hmdp.ai.v2;

import com.hmdp.ai.v2.grounding.*;
import com.hmdp.ai.v2.plan.*;
import com.hmdp.ai.v2.reducer.*;
import com.hmdp.ai.v2.semantic.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class V2DomainGoldenTest {
    @Test void critiqueIsNotRejectAndSelectHasOnlyOneCanonicalRepresentation() {
        EntityFeedback.EntityFeedbackItem critique = new EntityFeedback.EntityFeedbackItem(new EntityReference.OrdinalRef(1), EntityFeedback.FeedbackKind.CRITIQUE, EntityFeedback.FeedbackAspect.PRICE);
        TurnSemantics turn = turn(List.of(), List.of(critique), List.of(new UserRequest.SelectRequest("select", new EntityReference.OrdinalRef(2))));
        assertNotEquals(EntityFeedback.FeedbackKind.REJECT, critique.kind());
        assertFalse(turn.entityFeedback().stream().map(Object::getClass).anyMatch(type -> type.getSimpleName().contains("Select")));
        CompilationResult result = new ExecutionPlanCompiler().compile(ground(turn, task("A", 1, "福州", 11, 12)), snapshot());
        assertInstanceOf(CompilationResult.StaticPlan.class, result);
        assertEquals(1, ((CompilationResult.StaticPlan) result).plan().groups().stream().flatMap(g -> g.actions().stream()).filter(ExecutionAction.EmitDomainEffectAction.class::isInstance).count());
    }

    @Test void criteriaClearIsDistinctFromUntouchedAndRejectsContradiction() {
        RequirementChange.CriteriaPatch clearBudget = new RequirementChange.CriteriaPatch(DiningCriteria.empty(), Set.of(RequirementChange.ClearedCriterion.BUDGET));
        RequirementChange.CriteriaPatch cuisineOnly = new RequirementChange.CriteriaPatch(new DiningCriteria(null, new DiningCriteria.CuisineCriteria("日料", List.of()), null, null, null, DiningCriteria.SemanticPreferences.empty()), Set.of());
        assertTrue(clearBudget.cleared().contains(RequirementChange.ClearedCriterion.BUDGET)); assertNull(cuisineOnly.fragment().budget());
        assertThrows(IllegalArgumentException.class, () -> new RequirementChange.CriteriaPatch(new DiningCriteria(null, null, new DiningCriteria.BudgetCriteria(null, BigDecimal.TEN), null, null, DiningCriteria.SemanticPreferences.empty()), Set.of(RequirementChange.ClearedCriterion.BUDGET)));
    }

    @Test void relativePreferenceNeverCreatesAbsoluteBudget() {
        RequirementChange preference = new RequirementChange.RelativePreference(DiningCriteria.PreferenceDimension.PRICE, RequirementChange.Direction.LOWER);
        assertFalse(preference instanceof RequirementChange.CriteriaPatch);
    }

    @Test void chainedGuardsUseProducerTopology() {
        TurnSemantics turn = turn(List.of(), List.of(), List.of(
                new UserRequest.FactQueryRequest("r1", new EntityReference.OrdinalRef(1), UserRequest.FactType.PRIVATE_ROOM),
                new UserRequest.FactQueryRequest("r2", new EntityReference.OrdinalRef(2), UserRequest.FactType.SOCKET),
                new UserRequest.SelectRequest("r3", new EntityReference.OrdinalRef(3))), List.of(
                new SemanticRelation("r1", new ObservationPredicate.BooleanEquals(true), "r2"),
                new SemanticRelation("r2", new ObservationPredicate.BooleanEquals(true), "r3")));
        ExecutionPlan plan = ((CompilationResult.StaticPlan) new ExecutionPlanCompiler().compile(ground(turn, task("A", 1, "福州", 1, 2, 3)), snapshot())).plan();
        assertEquals(Set.of("G1"), plan.groups().get(1).dependsOnGroups()); assertEquals(Set.of("G2"), plan.groups().get(2).dependsOnGroups());
    }

    @Test void taskSelectorAndVisibleBatchAreDeterministic() {
        TaskView a = task("A", 1, "福州", 101, 102); TaskView b = task("B", 2, "杭州", 201, 202); TaskView c = task("C", 3, "厦门", 301);
        TurnSemantics turn = new TurnSemantics(TaskDirective.RESTORE, List.of(), List.of(), List.of(new UserRequest.FactQueryRequest("review", new EntityReference.OrdinalRef(2), UserRequest.FactType.REVIEW)), List.of(), List.of(new EntityReference.TaskRef(new TaskSelector.Earliest())));
        EffectiveTaskContext context = new EffectiveTaskContextResolver().resolve("C", List.of(b, a, c), turn);
        assertEquals("A", context.task().taskId());
        assertEquals(102L, ((GroundedReference.ShopIdentity) new GroundingResolver().ground(turn, context).requests().getFirst().operands().getFirst()).shopId());
    }

    @Test void batchFeedbackBindsCurrentVisibleBatchOnly() {
        TurnSemantics turn = turn(List.of(), List.of(new EntityFeedback.BatchFeedback(new BatchRef(BatchRef.BatchSelector.CURRENT_VISIBLE), EntityFeedback.BatchPolarity.NEGATIVE, EntityFeedback.FeedbackAspect.UNSPECIFIED)), List.of());
        GroundedTurn grounded = ground(turn, task("A", 1, "福州", 1, 2));
        assertEquals("A-batch", ((GroundedReference.BatchIdentity) grounded.feedback().getFirst().operands().getFirst()).batchId());
    }

    @Test void unresolvedAndAmbiguousReferencesAreClarificationOutcomes() {
        TaskView task = task("A", 1, "福州", 1, 2);
        TurnSemantics ordinal = turn(List.of(), List.of(), List.of(new UserRequest.FactQueryRequest("f", new EntityReference.OrdinalRef(3), UserRequest.FactType.SOCKET)));
        assertInstanceOf(GroundingResult.NeedsClarification.class, new GroundingResolver().resolve(ordinal, new EffectiveTaskContext(task, false)));
        TurnSemantics named = turn(List.of(), List.of(), List.of(new UserRequest.FactQueryRequest("f", new EntityReference.NamedEntityRef("店"), UserRequest.FactType.SOCKET)));
        assertInstanceOf(GroundingResult.NeedsClarification.class, new GroundingResolver().resolve(named, new EffectiveTaskContext(task, false)));
        TurnSemantics missingTask = new TurnSemantics(TaskDirective.RESTORE, List.of(), List.of(), List.of(), List.of(), List.of(new EntityReference.TaskRef(new TaskSelector.MatchContext("DINING", "北京"))));
        assertInstanceOf(EffectiveTaskContextResult.NeedsClarification.class, new EffectiveTaskContextResolver().resolveResult("A", List.of(task), missingTask));
    }

    @Test void directStaticAndAdaptiveAreExplicitCompilationVariants() {
        GroundedTurn fact = ground(turn(List.of(), List.of(), List.of(new UserRequest.FactQueryRequest("f", new EntityReference.OrdinalRef(1), UserRequest.FactType.SOCKET))), task("A", 1, "福州", 1));
        GroundedTurn search = ground(turn(List.of(), List.of(), List.of(new UserRequest.RecommendationRequest("s"))), task("A", 1, "福州", 1));
        GroundedTurn explore = ground(turn(List.of(), List.of(), List.of(new UserRequest.ExploreRequest("e", new EntityReference.OrdinalRef(1), true))), task("A", 1, "福州", 1));
        assertInstanceOf(CompilationResult.Direct.class, new ExecutionPlanCompiler().compile(fact, snapshot()));
        assertInstanceOf(CompilationResult.StaticPlan.class, new ExecutionPlanCompiler().compile(search, snapshot()));
        assertInstanceOf(CompilationResult.Adaptive.class, new ExecutionPlanCompiler().compile(explore, snapshot()));
    }

    @Test void conditionalFallbackDoesNotEnterPreReducerButAppliesAfterEffect() {
        V2TaskState initial = new V2TaskState(new DiningCriteria(null, new DiningCriteria.CuisineCriteria("火锅", List.of()), new DiningCriteria.BudgetCriteria(null, BigDecimal.valueOf(150)), null, null, DiningCriteria.SemanticPreferences.empty()), List.of(), Set.of(), Set.of());
        RequirementChange.CriteriaPatch bbq = new RequirementChange.CriteriaPatch(new DiningCriteria(null, new DiningCriteria.CuisineCriteria("烧烤", List.of()), null, null, null, DiningCriteria.SemanticPreferences.empty()), Set.of());
        TurnSemantics turn = turn(List.of(new RequirementChange.RelaxationAuthorization(DiningCriteria.PreferenceDimension.DISTANCE), new RequirementChange.RequirementLock(DiningCriteria.PreferenceDimension.PRICE), new RequirementChange.ConditionalRequirementChange("search", new ObservationPredicate.ResultStateIs(ObservationPredicate.ResultState.EMPTY), bbq)), List.of(), List.of(new UserRequest.RecommendationRequest("search")));
        V2TaskState pre = new PreExecutionReducer().reduce(initial, turn);
        assertEquals("火锅", pre.criteria().cuisine().include()); assertTrue(pre.locked().contains(DiningCriteria.PreferenceDimension.PRICE));
        ExecutionPlan fallbackPlan = ((CompilationResult.StaticPlan) new ExecutionPlanCompiler().compile(ground(turn, task("A", 1, "福州", 1)), snapshot())).plan();
        assertEquals("烧烤", ((ExecutionAction.SearchAction) fallbackPlan.groups().get(1).actions().getFirst()).spec().criteria().cuisine().include());
        V2TaskState post = new PostExecutionReducer().applyConditionalCuisine(pre, bbq, new ExecutionAction.DomainEffect.CuisineChangedTo("烧烤"));
        assertEquals("烧烤", post.criteria().cuisine().include());
    }

    @Test void searchSpecFreezesCommittedCausalInput() {
        PlanningSnapshot snapshot = snapshot();
        GroundedTurn turn = ground(turn(List.of(new RequirementChange.RelativePreference(DiningCriteria.PreferenceDimension.PRICE, RequirementChange.Direction.LOWER)), List.of(), List.of(new UserRequest.AlternativesRequest("a", 2))), task("A", 1, "福州", 1));
        ExecutionPlan plan = ((CompilationResult.StaticPlan) new ExecutionPlanCompiler().compile(turn, snapshot)).plan();
        SearchSpec spec = ((ExecutionAction.SearchAction) plan.groups().getFirst().actions().getFirst()).spec();
        assertEquals(7, spec.baseMemoryVersion()); assertEquals("A", spec.taskId()); assertEquals(BigDecimal.valueOf(150), spec.criteria().budget().hardMax());
    }

    private TurnSemantics turn(List<RequirementChange> changes, List<EntityFeedback> feedback, List<UserRequest> requests) { return new TurnSemantics(TaskDirective.CONTINUE, changes, feedback, requests, List.of()); }
    private TurnSemantics turn(List<RequirementChange> changes, List<EntityFeedback> feedback, List<UserRequest> requests, List<SemanticRelation> relations) { return new TurnSemantics(TaskDirective.CONTINUE, changes, feedback, requests, relations); }
    private GroundedTurn ground(TurnSemantics turn, TaskView task) { return new GroundingResolver().ground(turn, new EffectiveTaskContext(task, false)); }
    private PlanningSnapshot snapshot() { return new PlanningSnapshot(7, "A", new DiningCriteria(null, null, new DiningCriteria.BudgetCriteria(BigDecimal.valueOf(100), BigDecimal.valueOf(150)), null, null, DiningCriteria.SemanticPreferences.empty()), List.of(new RequirementChange.RelativePreference(DiningCriteria.PreferenceDimension.PRICE, RequirementChange.Direction.LOWER)), Set.of(99L), "福州"); }
    private TaskView task(String id, int order, String city, long... shops) { return new TaskView(id, order, "DINING", city, new TaskView.RecommendationBatchView(id + "-batch", java.util.stream.LongStream.of(shops).mapToObj(s -> new TaskView.ShopView(s, "店" + s)).toList()), null); }
}
