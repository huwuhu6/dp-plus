package com.hmdp.ai.service;

import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.ChatMessageRequest;
import com.hmdp.ai.dto.SemanticAct;
import com.hmdp.ai.dto.StructuredUnderstandingResult;
import com.hmdp.ai.dto.TurnSemanticIR;
import com.hmdp.ai.dto.DecisionResponse;
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
    void lazyInvocationIsMemoizedAcrossRoutingAndExtractionTriggers() {
        StructuredUnderstandingService structured = mock(StructuredUnderstandingService.class);
        StructuredUnderstandingResult valid = validRecommendation();
        when(structured.understand(any(), any(), any())).thenReturn(valid);
        ChatOrchestrationService service = configured(structured, "shadow");
        ChatProcessingContext context = context(message("推荐火锅"));

        ReflectionTestUtils.invokeMethod(service, "ensureStructuredUnderstanding", context, "ROUTING_ESCALATION");
        ReflectionTestUtils.invokeMethod(service, "ensureStructuredUnderstanding", context, "CONSTRAINT_EXTRACTION");

        assertTrue(context.isStructuredInvoked());
        assertEquals("ROUTING_ESCALATION", context.getStructuredInvocationTrigger());
        assertEquals(valid, context.getStructuredUnderstandingResult());
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "useStructuredActive", context));
        verify(structured).understand(any(), any(), any());
    }

    @Test
    void activeConsumesSafeStructuredResultOnlyAtRoutingEscalation() {
        StructuredUnderstandingService structured = mock(StructuredUnderstandingService.class);
        when(structured.understand(any(), any(), any())).thenReturn(validRecommendation());
        ChatOrchestrationService service = configured(structured, "active");
        ChatMemoryService memory = mock(ChatMemoryService.class);
        ReflectionTestUtils.setField(service, "chatMemoryService", memory);
        when(memory.findLatestDecisionSessionId(any())).thenReturn(null);
        ChatProcessingContext context = context(message("随便说点什么"));

        service.route(context);

        assertEquals(ChatProcessingAction.START_DECISION, context.getAction());
        assertEquals("STRUCTURED_UNDERSTANDING", context.getRoutingAssessment().getSource());
        assertEquals("ROUTING_ESCALATION", context.getStructuredInvocationTrigger());
        verify(structured).understand(any(), any(), any());
    }

    @Test
    void unsafeActiveResultFallsBackToLegacyRouting() {
        StructuredUnderstandingService structured = mock(StructuredUnderstandingService.class);
        StructuredUnderstandingResult unsafe = validRecommendation();
        unsafe.getIr().setAmbiguities(java.util.List.of("ambiguous"));
        when(structured.understand(any(), any(), any())).thenReturn(unsafe);
        ChatOrchestrationService service = configured(structured, "active");
        ChatMemoryService memory = mock(ChatMemoryService.class);
        ReflectionTestUtils.setField(service, "chatMemoryService", memory);
        when(memory.findLatestDecisionSessionId(any())).thenReturn(null);
        ChatProcessingContext context = context(message("随便说点什么"));

        service.route(context);

        assertEquals(ChatProcessingAction.GENERAL_CHAT, context.getAction());
        assertEquals("MODEL", context.getRoutingAssessment().getSource());
        verify(structured).understand(any(), any(), any());
    }

    @Test
    void activeUsesSafeResultAndUnsafeResultRemainsLegacyEligible() {
        ChatOrchestrationService service = configured(mock(StructuredUnderstandingService.class), "active");
        ChatProcessingContext context = context(message("推荐火锅"));
        context.setStructuredUnderstandingResult(validRecommendation());
        context.setStructuredUnderstanding(context.getStructuredUnderstandingResult().getIr());
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "useStructuredActive", context));

        context.getStructuredUnderstanding().setAmbiguities(java.util.List.of("ambiguous"));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "useStructuredActive", context));
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

    private StructuredUnderstandingResult validRecommendation() {
        SemanticAct act = new SemanticAct();
        act.setType(SemanticAct.Type.REQUEST_RECOMMENDATION);
        TurnSemanticIR ir = new TurnSemanticIR();
        ir.getActs().add(act);
        StructuredUnderstandingResult result = new StructuredUnderstandingResult();
        result.setValid(true);
        result.setIr(ir);
        return result;
    }
}
