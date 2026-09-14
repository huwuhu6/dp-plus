package com.hmdp.ai.v2.runtime;

import com.hmdp.ai.dto.DecisionTaskState;
import com.hmdp.ai.dto.RecommendationBatch;
import com.hmdp.ai.dto.RecommendationCandidateRef;
import com.hmdp.ai.v2.grounding.*;
import com.hmdp.ai.v2.plan.*;
import com.hmdp.ai.v2.reducer.*;
import com.hmdp.ai.v2.semantic.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class V2RuntimeHardeningTest {
    @Test
    void conditionalPatchAppliesAfterEmptyThenSuccessfulOrEmptyFallbackButNotFailure() {
        RequirementChange.CriteriaPatch bbq = new RequirementChange.CriteriaPatch(
                new DiningCriteriaPatch(null, new DiningCriteria.CuisineCriteria("烧烤", List.of()), null, null, null, null), Set.of());
        TurnSemantics semantics = new TurnSemantics(TaskDirective.CONTINUE,
                List.of(new RequirementChange.ConditionalRequirementChange("search", new ObservationPredicate.ResultStateIs(ObservationPredicate.ResultState.EMPTY), bbq)),
                List.of(), List.of(new UserRequest.RecommendationRequest("search")), List.of());
        GroundedTurn turn = new GroundingResolver().ground(semantics,
                new EffectiveTaskContext(new TaskView("A", 0, "DINING", null, null, null), false));
        PlanningSnapshot snapshot = new PlanningSnapshot(1, "A",
                new DiningCriteria(null, new DiningCriteria.CuisineCriteria("火锅", List.of()), null, null, null, DiningCriteria.SemanticPreferences.empty()),
                List.of(), Set.of(), Set.of(), Set.of(), null);
        ExecutionPlan plan = ((CompilationResult.StaticPlan) new ExecutionPlanCompiler().compile(turn, snapshot)).plan();

        for (ExecutionObservation.Status fallbackStatus : List.of(ExecutionObservation.Status.SUCCESS, ExecutionObservation.Status.EMPTY)) {
            AtomicInteger searchCount = new AtomicInteger();
            StaticPlanExecutor.ExecutionResult execution = new StaticPlanExecutor().execute(plan, action -> {
                if (action instanceof ExecutionAction.SearchAction) {
                    int call = searchCount.getAndIncrement();
                    return new ExecutionObservation(action.requestId(), call == 0 ? ExecutionObservation.Status.EMPTY : fallbackStatus,
                            List.of(), null, List.of(), null, null);
                }
                if (action instanceof ExecutionAction.EmitDomainEffectAction effect)
                    return new ExecutionObservation(action.requestId(), ExecutionObservation.Status.SUCCESS, List.of(), null,
                            List.of(effect.effect()), null, null);
                throw new AssertionError(action);
            });
            V2TaskState post = new PostExecutionReducer().apply(new PreExecutionReducer().reduce(
                    new V2TaskState(snapshot.criteria(), List.of(), Set.of(), Set.of()), semantics), execution);
            assertEquals("烧烤", post.criteria().cuisine().include());
        }

        AtomicInteger searchCount = new AtomicInteger();
        StaticPlanExecutor.ExecutionResult failed = new StaticPlanExecutor().execute(plan, action -> {
            if (action instanceof ExecutionAction.SearchAction) {
                int call = searchCount.getAndIncrement();
                return new ExecutionObservation(action.requestId(), call == 0 ? ExecutionObservation.Status.EMPTY : ExecutionObservation.Status.FAILURE,
                        List.of(), null, List.of(), null, "retrieval failed");
            }
            return new ExecutionObservation(action.requestId(), ExecutionObservation.Status.SUCCESS, List.of(), null,
                    List.of(((ExecutionAction.EmitDomainEffectAction) action).effect()), null, null);
        });
        V2TaskState unchanged = new PostExecutionReducer().apply(new V2TaskState(snapshot.criteria(), List.of(), Set.of(), Set.of()), failed);
        assertEquals("火锅", unchanged.criteria().cuisine().include());
    }

    @Test
    void critiqueAndBatchNegativeStayInLedgerWhileOnlyEntityRejectIsPermanent() {
        DecisionTaskState task = new DecisionTaskState(); task.setTaskId("A");
        RecommendationBatch batch = new RecommendationBatch(); batch.setBatchId("B1");
        for (long id : List.of(11L, 12L, 13L)) {
            RecommendationCandidateRef candidate = new RecommendationCandidateRef(); candidate.setShopId(id); candidate.setShopName("店" + id);
            batch.getCandidates().add(candidate);
        }
        task.setRecommendationBatches(new ArrayList<>(List.of(batch)));
        TaskView view = new TaskView("A", 0, "DINING", null,
                new TaskView.RecommendationBatchView("B1", List.of(new TaskView.ShopView(11L, "店11"),
                        new TaskView.ShopView(12L, "店12"), new TaskView.ShopView(13L, "店13"))), null);
        TurnSemantics semantics = new TurnSemantics(TaskDirective.CONTINUE, List.of(), List.of(
                new EntityFeedback.EntityFeedbackItem(new EntityReference.OrdinalRef(1), EntityFeedback.FeedbackKind.CRITIQUE, EntityFeedback.FeedbackAspect.PRICE),
                new EntityFeedback.EntityFeedbackItem(new EntityReference.OrdinalRef(2), EntityFeedback.FeedbackKind.REJECT, EntityFeedback.FeedbackAspect.UNSPECIFIED),
                new EntityFeedback.BatchFeedback(new BatchRef(BatchRef.BatchSelector.CURRENT_VISIBLE), EntityFeedback.BatchPolarity.NEGATIVE, EntityFeedback.FeedbackAspect.PRICE)),
                List.of(), List.of());
        GroundedTurn grounded = new GroundingResolver().ground(semantics, new EffectiveTaskContext(view, false));

        V2TaskState state = new PreExecutionReducer().reduce(new V2TaskStateGateway().read(task), semantics, grounded);

        assertEquals(Set.of(12L), state.rejectedShopIds());
        assertEquals(3, state.feedbackLedger().size());
        assertTrue(state.feedbackLedger().stream().anyMatch(entry -> entry.scope() == V2FeedbackEntry.Scope.BATCH
                && "B1".equals(entry.batchId()) && entry.batchPolarity() == EntityFeedback.BatchPolarity.NEGATIVE));
        assertFalse(state.rejectedShopIds().containsAll(Set.of(11L, 12L, 13L)));
    }

    @Test
    void generalKnowledgeCompareAndAdaptiveAreExplicitUnsupportedOutcomes() {
        V2ActionHandler handler = new V2ActionHandler();
        ExecutionObservation general = handler.execute(new ExecutionAction.GeneralAnswerAction("general-1", "用户主题不能原样回显"));
        ExecutionObservation compare = handler.execute(new ExecutionAction.CompareAction("compare-1", List.of(), List.of()));
        assertEquals(ExecutionObservation.Status.UNSUPPORTED, general.status());
        assertFalse(general.detail().contains("用户主题不能原样回显"));
        assertEquals(ExecutionObservation.Status.UNSUPPORTED, compare.status());
        StaticPlanExecutor.ExecutionResult adaptive = new V2ChatOrchestrator().execute(
                new CompilationResult.Adaptive(new AdaptiveResearchContract(List.of(), List.of(), 4, 4,
                        java.time.Duration.ofSeconds(1), true, true)));
        assertEquals(ExecutionObservation.Status.UNSUPPORTED, adaptive.observations().getFirst().status());
    }
}
