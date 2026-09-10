package com.hmdp.ai.service;

import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.ChatMessageRequest;
import com.hmdp.ai.dto.RoutingFusionV2Result;
import com.hmdp.ai.dto.RoutingSemanticActV2;
import com.hmdp.ai.dto.RoutingSemanticIRV2;
import com.hmdp.ai.entity.AiChatSession;
import com.hmdp.ai.service.pipeline.ChatProcessingAction;
import com.hmdp.ai.service.pipeline.ChatProcessingContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RoutingFusionV2InvocationTest {
    @Test
    void deterministicRecommendationUsesLegacyExtractionWithoutV2() {
        StructuredUnderstandingService v2 = mock(StructuredUnderstandingService.class);
        ChatOrchestrationService service = configured(v2, "shadow");
        ChatProcessingContext context = context("推荐火锅");

        service.route(context);

        assertEquals(ChatProcessingAction.START_DECISION, context.getAction());
        verifyNoInteractions(v2);
    }

    @Test
    void routingEscalationInvokesV2OnceAndMemoizesResult() {
        StructuredUnderstandingService v2 = mock(StructuredUnderstandingService.class);
        when(v2.understandRoutingFusion(any(), any(), any())).thenReturn(fallback());
        ChatOrchestrationService service = configured(v2, "shadow");
        ChatProcessingContext context = context("随便说点什么");

        service.route(context);
        ReflectionTestUtils.invokeMethod(service, "ensureRoutingFusionV2", context);

        assertTrue(context.isStructuredInvoked());
        assertEquals("ROUTING_ESCALATION", context.getStructuredInvocationTrigger());
        verify(v2).understandRoutingFusion(any(), any(), any());
    }

    @Test
    void activeUsesReusableV2CriteriaWithoutCallingLegacyExtractor() {
        StructuredUnderstandingService v2 = mock(StructuredUnderstandingService.class);
        when(v2.understandRoutingFusion(any(), any(), any())).thenReturn(validRecommendation());
        ChatOrchestrationService service = configured(v2, "active");
        ChatProcessingContext context = context("随便说点什么");

        service.route(context);

        assertEquals(ChatProcessingAction.START_DECISION, context.getAction());
        assertEquals("ROUTING", context.getStructuredApplyPoint());
        assertTrue(context.isStructuredApplied());
        verify(v2).understandRoutingFusion(any(), any(), any());
    }

    private ChatOrchestrationService configured(StructuredUnderstandingService v2, String mode) {
        ChatOrchestrationService service = new ChatOrchestrationService();
        ReflectionTestUtils.setField(service, "aiProperties", properties(mode));
        ReflectionTestUtils.setField(service, "structuredUnderstandingService", v2);
        ReflectionTestUtils.setField(service, "structuredUnderstandingAdapter", new StructuredUnderstandingAdapter());
        ChatMemoryService memory = mock(ChatMemoryService.class);
        when(memory.findLatestDecisionSessionId(any())).thenReturn(null);
        ReflectionTestUtils.setField(service, "chatMemoryService", memory);
        return service;
    }

    private ChatProcessingContext context(String message) {
        ChatMessageRequest request = new ChatMessageRequest();
        request.setMessage(message);
        ChatProcessingContext context = new ChatProcessingContext(request, null);
        context.setOriginalMessage(message);
        context.setEffectiveMessage(message);
        context.setChatId("v2-test");
        context.setChatSession(new AiChatSession());
        return context;
    }

    private AiProperties properties(String mode) {
        AiProperties properties = new AiProperties();
        AiProperties.StructuredUnderstandingProperties structured = new AiProperties.StructuredUnderstandingProperties();
        structured.setMode(mode);
        properties.setStructuredUnderstanding(structured);
        return properties;
    }

    private RoutingFusionV2Result fallback() {
        return RoutingFusionV2Result.fallback("INVALID_EVIDENCE_OR_SCHEMA", 1L);
    }

    private RoutingFusionV2Result validRecommendation() {
        RoutingSemanticActV2 act = new RoutingSemanticActV2();
        act.setType(RoutingSemanticActV2.Type.REQUEST_RECOMMENDATION);
        act.setEvidenceText("随便说点什么");
        RoutingSemanticIRV2 ir = new RoutingSemanticIRV2();
        ir.getActs().add(act);
        RoutingFusionV2Result result = new RoutingFusionV2Result();
        result.setIr(ir);
        result.setValid(true);
        result.setCriteriaReusable(true);
        return result;
    }
}
