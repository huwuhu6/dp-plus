package com.hmdp.ai.service;

import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.ChatMessageRequest;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.dto.RoutingFusionV2Result;
import com.hmdp.ai.dto.RoutingSemanticActV2;
import com.hmdp.ai.dto.RoutingSemanticIRV2;
import com.hmdp.ai.entity.AiChatSession;
import com.hmdp.ai.service.pipeline.ChatProcessingAction;
import com.hmdp.ai.service.pipeline.ChatProcessingContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StructuredUnderstandingInvocationTest {

    @Test
    void bootstrapDoesNotEagerlyInvokeStructuredUnderstanding() {
        ChatOrchestrationService service = new ChatOrchestrationService();
        StructuredUnderstandingService structured = mock(StructuredUnderstandingService.class);
        ChatMemoryService memory = mock(ChatMemoryService.class);
        ConversationStateService state = mock(ConversationStateService.class);
        AiChatSession session = new AiChatSession();
        session.setChatId("lazy-bootstrap");
        ReflectionTestUtils.setField(service, "aiProperties", properties("shadow"));
        ReflectionTestUtils.setField(service, "structuredUnderstandingService", structured);
        ReflectionTestUtils.setField(service, "chatMemoryService", memory);
        ReflectionTestUtils.setField(service, "conversationStateService", state);
        ReflectionTestUtils.setField(service, "decisionService", mock(ConsumptionDecisionService.class));
        when(memory.resolveChatId(any())).thenReturn("lazy-bootstrap");
        when(memory.load("lazy-bootstrap")).thenReturn(java.util.Collections.emptyList());
        when(state.getOrCreate("lazy-bootstrap")).thenReturn(session);
        when(state.workingMemory(session)).thenReturn(new com.hmdp.ai.dto.ConversationWorkingMemory());

        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage("你好");
        service.bootstrap(new ChatProcessingContext(request, null));

        verifyNoInteractions(structured);
    }

    @Test
    void deterministicSelectedOptionAndNonDiningGuardDoNotInvokeStructuredUnderstanding() {
        StructuredUnderstandingService structured = mock(StructuredUnderstandingService.class);
        ChatOrchestrationService service = configured(structured, "active");

        ChatMessageRequest selected = new ChatMessageRequest();
        selected.setMessage("结束");
        selected.setSelectedOptionId("END_DECISION");
        ChatProcessingContext selectedContext = context(selected);
        DecisionResponse paused = new DecisionResponse();
        paused.setStatus("WAITING_RELAXATION");
        selectedContext.setActiveDecision(paused);
        service.route(selectedContext);
        assertEquals(ChatProcessingAction.DECISION_EVENT, selectedContext.getAction());

        ChatMessageRequest nonDining = new ChatMessageRequest();
        nonDining.setMessage("附近有医院吗");
        ChatProcessingContext nonDiningContext = context(nonDining);
        service.route(nonDiningContext);
        assertEquals(ChatProcessingAction.GENERAL_CHAT, nonDiningContext.getAction());
        verifyNoInteractions(structured);
    }

    @Test
    void activeConsumesSafeStructuredResultOnlyAtRoutingEscalation() {
        StructuredUnderstandingService structured = mock(StructuredUnderstandingService.class);
        when(structured.understandRoutingFusion(any(), any(), any())).thenReturn(validRoutingRecommendation());
        ChatOrchestrationService service = configured(structured, "active");
        ChatMemoryService memory = mock(ChatMemoryService.class);
        ReflectionTestUtils.setField(service, "chatMemoryService", memory);
        when(memory.findLatestDecisionSessionId(any())).thenReturn(null);
        ChatProcessingContext context = context(message("随便说点什么"));

        service.route(context);

        assertEquals(ChatProcessingAction.START_DECISION, context.getAction());
        assertEquals("STRUCTURED_UNDERSTANDING", context.getRoutingAssessment().getSource());
        assertEquals("ROUTING_ESCALATION", context.getStructuredInvocationTrigger());
        assertTrue(context.isStructuredApplied());
        assertEquals("ROUTING", context.getStructuredApplyPoint());
        verify(structured).understandRoutingFusion(any(), any(), any());
    }

    @Test
    void unsafeActiveResultFallsBackToLegacyRouting() {
        StructuredUnderstandingService structured = mock(StructuredUnderstandingService.class);
        when(structured.understandRoutingFusion(any(), any(), any()))
                .thenReturn(RoutingFusionV2Result.fallback("INVALID_EVIDENCE_OR_SCHEMA", 1L));
        ChatOrchestrationService service = configured(structured, "active");
        ChatMemoryService memory = mock(ChatMemoryService.class);
        ReflectionTestUtils.setField(service, "chatMemoryService", memory);
        when(memory.findLatestDecisionSessionId(any())).thenReturn(null);
        ChatProcessingContext context = context(message("随便说点什么"));

        service.route(context);

        assertEquals(ChatProcessingAction.GENERAL_CHAT, context.getAction());
        assertEquals("MODEL", context.getRoutingAssessment().getSource());
        assertTrue(context.isStructuredInvoked());
        assertFalse(context.isStructuredApplied());
        assertEquals("NOT_APPLIED", context.getStructuredApplyPoint());
        verify(structured).understandRoutingFusion(any(), any(), any());
    }

    @Test
    void deterministicExtractionDoesNotInvokeRoutingFusionV2() {
        StructuredUnderstandingService structured = mock(StructuredUnderstandingService.class);
        ChatOrchestrationService service = configured(structured, "active");
        ConstraintExtractor extractor = mock(ConstraintExtractor.class);
        com.hmdp.ai.dto.DecisionConstraints extracted = new com.hmdp.ai.dto.DecisionConstraints();
        extracted.setBudgetDirection(-1);
        when(extractor.extract(any(), any())).thenReturn(extracted);
        ReflectionTestUtils.setField(service, "constraintExtractor", extractor);
        ChatProcessingContext context = context(message("便宜点"));

        ReflectionTestUtils.invokeMethod(service, "ensureCriteriaDelta", context);

        assertFalse(context.isStructuredInvoked());
        assertFalse(context.isStructuredApplied());
        assertEquals(-1, context.getCriteriaDelta().getBudgetDirection());
        verifyNoInteractions(structured);
    }

    @Test
    void deterministicStartDecisionUsesLegacyExtractionInRealCriteriaReduction() {
        StructuredUnderstandingService structured = mock(StructuredUnderstandingService.class);
        ChatOrchestrationService service = configured(structured, "active");
        ConstraintExtractor extractor = mock(ConstraintExtractor.class);
        com.hmdp.ai.dto.DecisionConstraints extracted = new com.hmdp.ai.dto.DecisionConstraints();
        extracted.setCuisine("火锅");
        when(extractor.extract(any(), any())).thenReturn(extracted);
        ConversationStateService state = mock(ConversationStateService.class);
        ConversationCriteriaMerger merger = mock(ConversationCriteriaMerger.class);
        ReflectionTestUtils.setField(service, "constraintExtractor", extractor);
        ReflectionTestUtils.setField(service, "conversationStateService", state);
        ReflectionTestUtils.setField(service, "criteriaMerger", merger);

        com.hmdp.ai.dto.ConversationWorkingMemory memory = new com.hmdp.ai.dto.ConversationWorkingMemory();
        com.hmdp.ai.dto.DecisionTaskState task = memory.ensureActiveTask();
        com.hmdp.ai.dto.DecisionConstraints previous = new com.hmdp.ai.dto.DecisionConstraints();
        com.hmdp.ai.dto.CriteriaMergeResult merged = new com.hmdp.ai.dto.CriteriaMergeResult();
        merged.setConstraints(new com.hmdp.ai.dto.DecisionConstraints());
        ChatProcessingContext context = context(message("推荐火锅"));
        context.setWorkingMemory(memory);

        when(state.activeCriteria(memory)).thenReturn(previous);
        when(state.activeTask(memory)).thenReturn(task);
        when(state.transitionTask(any(), any(), any())).thenReturn(
                new ConversationStateService.TaskTransition("UPDATE", "REFINEMENT_OR_PARTIAL_REPLACEMENT", task.getTaskId(), task.getTaskId()));
        when(state.latestCandidatePool(memory)).thenReturn(java.util.Collections.emptyList());
        when(state.shownShopIds(memory)).thenReturn(java.util.Collections.emptyList());
        when(state.workingMemory(context.getChatSession())).thenReturn(memory);
        when(merger.merge(any(), any(), any(), any(), any(), any(), any())).thenReturn(merged);

        service.route(context);
        assertEquals(ChatProcessingAction.START_DECISION, context.getAction());
        service.reduceCriteria(context);

        assertFalse(context.isStructuredInvoked());
        assertFalse(context.isStructuredApplied());
        assertEquals("NOT_APPLIED", context.getStructuredApplyPoint());
        assertEquals("火锅", context.getCriteriaDelta().getCuisine());
        verify(extractor).extract(any(), any());
        verifyNoInteractions(structured);
    }

    private ChatOrchestrationService configured(StructuredUnderstandingService structured, String mode) {
        ChatOrchestrationService service = new ChatOrchestrationService();
        ReflectionTestUtils.setField(service, "aiProperties", properties(mode));
        ReflectionTestUtils.setField(service, "structuredUnderstandingService", structured);
        ReflectionTestUtils.setField(service, "structuredUnderstandingAdapter", new StructuredUnderstandingAdapter());
        return service;
    }

    private ChatProcessingContext context(ChatMessageRequest request) {
        ChatProcessingContext context = new ChatProcessingContext(request, null);
        context.setOriginalMessage(request.getMessage());
        context.setEffectiveMessage(request.getMessage());
        context.setChatHistory(java.util.Collections.emptyList());
        context.setChatId("structured-test");
        context.setChatSession(new AiChatSession());
        return context;
    }

    private ChatMessageRequest message(String text) {
        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage(text);
        return request;
    }

    private AiProperties properties(String mode) {
        AiProperties properties = new AiProperties();
        AiProperties.StructuredUnderstandingProperties structured = new AiProperties.StructuredUnderstandingProperties();
        structured.setMode(mode);
        properties.setStructuredUnderstanding(structured);
        return properties;
    }

    private RoutingFusionV2Result validRoutingRecommendation() {
        RoutingSemanticActV2 act = new RoutingSemanticActV2();
        act.setType(RoutingSemanticActV2.Type.REQUEST_RECOMMENDATION);
        RoutingSemanticIRV2 ir = new RoutingSemanticIRV2();
        ir.getActs().add(act);
        RoutingFusionV2Result result = new RoutingFusionV2Result();
        result.setIr(ir);
        result.setValid(true);
        result.setCriteriaReusable(true);
        return result;
    }

}
