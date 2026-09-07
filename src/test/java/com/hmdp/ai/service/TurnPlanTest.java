package com.hmdp.ai.service;

import com.hmdp.ai.dto.CriteriaIntent;
import com.hmdp.ai.dto.ContextRewriteResult;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.ReferenceIntent;
import com.hmdp.ai.dto.RecommendationBatch;
import com.hmdp.ai.dto.RecommendationCandidateRef;
import com.hmdp.ai.dto.ResolvedShopReference;
import com.hmdp.ai.service.pipeline.ChatProcessingAction;
import com.hmdp.ai.service.pipeline.ChatProcessingContext;
import com.hmdp.ai.dto.ChatMessageRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TurnPlanTest {
    @Test
    void startDecisionCarriesCriteriaDelta() {
        ChatOrchestrationService service = new ChatOrchestrationService();
        ChatMessageRequest request = new ChatMessageRequest(); request.setMessage("找火锅");
        ChatProcessingContext context = new ChatProcessingContext(request, null);
        context.setOriginalMessage(request.getMessage()); context.setEffectiveMessage(request.getMessage());

        ReflectionTestUtils.invokeMethod(service, "selectAction", context, ChatProcessingAction.START_DECISION, "test");

        assertEquals(CriteriaIntent.APPLY_DELTA, context.getTurnPlan().getCriteriaIntent());
        assertEquals(ChatProcessingAction.START_DECISION, context.getTurnPlan().getExecutionAction());
    }

    @Test
    void plainFollowUpDoesNotApplyCriteriaDelta() {
        ChatOrchestrationService service = new ChatOrchestrationService();
        ChatMessageRequest request = new ChatMessageRequest(); request.setMessage("这家几点关门");
        ChatProcessingContext context = new ChatProcessingContext(request, null);
        context.setOriginalMessage(request.getMessage()); context.setEffectiveMessage(request.getMessage());

        ReflectionTestUtils.invokeMethod(service, "selectAction", context, ChatProcessingAction.BUSINESS_FOLLOW_UP, "test");

        assertEquals(CriteriaIntent.NONE, context.getTurnPlan().getCriteriaIntent());
        assertEquals(ChatProcessingAction.BUSINESS_FOLLOW_UP, context.getTurnPlan().getExecutionAction());
    }

    @Test
    void compoundFollowUpCanCarryStructuredDeltaWithoutSecondAction() {
        ChatOrchestrationService service = new ChatOrchestrationService();
        TurnUnderstandingService turnSemantics = new TurnUnderstandingService();
        ConstraintExtractor extractor = mock(ConstraintExtractor.class);
        DecisionConstraints delta = new DecisionConstraints(); delta.setBudgetDirection(-1);
        when(extractor.extract("第一家太贵，第二家有插座吗")).thenReturn(delta);
        ReflectionTestUtils.setField(service, "constraintExtractor", extractor);
        ReflectionTestUtils.setField(service, "turnUnderstandingService", turnSemantics);

        ChatMessageRequest request = new ChatMessageRequest(); request.setMessage("第一家太贵，第二家有插座吗");
        ChatProcessingContext context = new ChatProcessingContext(request, null);
        context.setOriginalMessage(request.getMessage()); context.setEffectiveMessage(request.getMessage());
        ContextRewriteResult rewrite = ContextRewriteResult.unchanged(request.getMessage(), "test");
        RecommendationBatch batch = new RecommendationBatch();
        RecommendationCandidateRef candidate = new RecommendationCandidateRef(); candidate.setShopId(1L); candidate.setShopName("A");
        batch.setCandidates(List.of(candidate));
        ReferenceIntent intent = new ReferenceIntent(ReferenceIntent.Scope.LATEST, 1, "第一家", 0, 3);
        rewrite.setResolvedReferences(List.of(new ResolvedShopReference(intent, batch, 1, 1L, "A")));
        context.setContextRewrite(rewrite);
        context.setTurnCommandSet(turnSemantics.understand(context.getOriginalMessage(), context.getEffectiveMessage(),
                List.of(), rewrite));

        ReflectionTestUtils.invokeMethod(service, "selectAction", context, ChatProcessingAction.BUSINESS_FOLLOW_UP, "test");

        assertEquals(CriteriaIntent.APPLY_DELTA, context.getTurnPlan().getCriteriaIntent());
        assertEquals(ChatProcessingAction.BUSINESS_FOLLOW_UP, context.getTurnPlan().getExecutionAction());
    }

    @Test
    void factQuestionCannotMutateFromExtractorValues() {
        ChatOrchestrationService service = new ChatOrchestrationService();
        TurnUnderstandingService turnSemantics = new TurnUnderstandingService();
        ConstraintExtractor extractor = mock(ConstraintExtractor.class);
        ReflectionTestUtils.setField(service, "turnUnderstandingService", turnSemantics);
        ReflectionTestUtils.setField(service, "constraintExtractor", extractor);

        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("第一家那个日本料理环境怎么样");
        ChatProcessingContext context = new ChatProcessingContext(request, null);
        context.setOriginalMessage(request.getMessage());
        context.setEffectiveMessage(request.getMessage());
        ContextRewriteResult rewrite = ContextRewriteResult.unchanged(request.getMessage(), "test");
        rewrite.setResolvedReferences(List.of(new ResolvedShopReference(
                new ReferenceIntent(ReferenceIntent.Scope.LATEST, 1, "第一家", 0, 3),
                new RecommendationBatch(), 1, 1L, "日本料理店")));
        context.setContextRewrite(rewrite);
        context.setTurnCommandSet(turnSemantics.understand(context.getOriginalMessage(), context.getEffectiveMessage(),
                List.of(), rewrite));

        DecisionConstraints extracted = new DecisionConstraints();
        extracted.setCuisine("日料");
        when(extractor.extract(request.getMessage())).thenReturn(extracted);

        ReflectionTestUtils.invokeMethod(service, "selectAction", context, ChatProcessingAction.BUSINESS_FOLLOW_UP, "test");

        assertEquals(CriteriaIntent.NONE, context.getTurnPlan().getCriteriaIntent());
        org.mockito.Mockito.verifyNoInteractions(extractor);
    }

    @Test
    void missingTurnSemanticsFailsClosedForReferenceMutation() {
        ChatOrchestrationService service = new ChatOrchestrationService();
        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("第一家太贵了");
        ChatProcessingContext context = new ChatProcessingContext(request, null);
        context.setOriginalMessage(request.getMessage());
        context.setEffectiveMessage(request.getMessage());
        ContextRewriteResult rewrite = ContextRewriteResult.unchanged(request.getMessage(), "test");
        rewrite.setResolvedReferences(List.of(new ResolvedShopReference(
                new ReferenceIntent(ReferenceIntent.Scope.LATEST, 1, "第一家", 0, 3),
                new RecommendationBatch(), 1, 1L, "店铺")));
        context.setContextRewrite(rewrite);
        DecisionConstraints extracted = new DecisionConstraints();
        extracted.setBudgetDirection(-1);
        context.setCriteriaDelta(extracted);

        ReflectionTestUtils.invokeMethod(service, "selectAction", context, ChatProcessingAction.BUSINESS_FOLLOW_UP, "test");

        assertEquals(CriteriaIntent.NONE, context.getTurnPlan().getCriteriaIntent());
    }
}
