package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.ChatMessageResponse;
import com.hmdp.ai.entity.AiConversationEvaluationCase;
import com.hmdp.ai.entity.AiConversationEvaluationCaseResult;
import com.hmdp.ai.entity.AiConversationEvaluationRun;
import com.hmdp.ai.mapper.AiAgentToolCallMapper;
import com.hmdp.ai.v2.runtime.SemanticInterpreter;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Infrastructure counterexamples, independent of model outputs and business gold. */
class V2EvaluationReliabilityTest {
    private final AiConversationEvaluationService service = new AiConversationEvaluationService();
    private final ChatOrchestrationService chat = mock(ChatOrchestrationService.class);

    V2EvaluationReliabilityTest() {
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "chatOrchestrationService", chat);
        ReflectionTestUtils.setField(service, "semanticInterpreter", mock(SemanticInterpreter.class));
        ReflectionTestUtils.setField(service, "toolCallMapper", mock(AiAgentToolCallMapper.class));
    }

    @Test void failedTurnCannotSatisfyEmptyStateOrPreservedRelation() {
        when(chat.chat(any(), isNull(), any())).thenThrow(new IllegalStateException("model failed"));
        AiConversationEvaluationCase item = item();
        item.setTurnsJson("[{\"message\":\"x\"},{\"message\":\"y\"}]");
        item.setExpectedErrorCount(2);
        item.setExpectedTurnStatesJson("[{\"turn\":1,\"memory\":{\"candidatePool\":{\"empty\":true}}}]");
        item.setExpectedRelationsJson("[{\"fromTurn\":1,\"toTurn\":2,\"type\":\"candidatePool\",\"relation\":\"PRESERVED\"}]");
        AiConversationEvaluationCaseResult result = evaluate(item);
        assertEquals(false, result.getMemoryMatched());
        assertTrue(result.getTurnOutputsJson().contains("TURN_SNAPSHOT_UNAVAILABLE"));
        assertFailed(result);
    }

    @Test void failedTurnCannotSatisfyNotSetOutcome() {
        when(chat.chat(any(), isNull(), any())).thenThrow(new IllegalStateException("model failed"));
        AiConversationEvaluationCase item = item(); item.setExpectedErrorCount(1);
        item.setExpectedV2OutcomesJson("[{\"turn\":1,\"assertions\":{\"selectedShopId\":{\"notSet\":true}}}]");
        AiConversationEvaluationCaseResult result = evaluate(item);
        assertEquals(false, result.getV2OutcomeMatched());
        assertFailed(result);
    }

    @Test void emptyAssertionsCannotCountAsVerifiedOutcome() {
        when(chat.chat(any(), isNull(), any())).thenReturn(new ChatMessageResponse());
        AiConversationEvaluationCase item = item();
        item.setExpectedV2OutcomesJson("[{\"turn\":1,\"assertions\":{}}]");
        assertFailed(evaluate(item));
    }

    @Test void malformedExpectationsAreRecordedAsInfrastructureError() {
        AiConversationEvaluationCase item = item(); item.setExpectedV2OutcomesJson("broken");
        AiConversationEvaluationCaseResult result = assertDoesNotThrow(() -> evaluate(item));
        assertNotNull(result.getErrorMessage()); assertFailed(result);
    }

    @Test void missingOptionalOutcomesAreNotCountedAsPassed() {
        when(chat.chat(any(), isNull(), any())).thenReturn(new ChatMessageResponse());
        AiConversationEvaluationCaseResult result = evaluate(item());
        assertNull(result.getV2OutcomeMatched());
        assertNull(result.getMemoryMatched());
        assertEquals(0, result.getExpectedV2OutcomeCount());
        AiConversationEvaluationRun run = finish(result);
        assertEquals(0, run.getV2OutcomeMatchedCount());
    }

    @Test void priorTurnVerificationCannotBeReusedWhenMemoryVersionIsUnchanged() {
        var versions = mock(WorkingMemoryVersionService.class);
        var events = mock(com.hmdp.ai.mapper.AiConversationEventMapper.class);
        ReflectionTestUtils.setField(service, "workingMemoryVersionService", versions);
        ReflectionTestUtils.setField(service, "conversationEventMapper", events);
        var row = new com.hmdp.ai.entity.AiWorkingMemory();
        row.setId(1L); row.setVersion(1); row.setMemoryJson("{\"tasks\":[]}");
        when(versions.latest(any())).thenReturn(row);
        var priorPost = new com.hmdp.ai.entity.AiConversationEvent();
        priorPost.setEventResult("{\"verificationStatus\":\"VERIFIED_PASS\"}");
        // 模拟第二轮澄清未写新版本：仅靠 memory id 查询会读到第一轮 POST。
        when(events.selectOne(any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?> query = invocation.getArgument(0);
            return query.getSqlSegment().contains("turn_no") ? null : priorPost;
        });
        when(chat.chat(any(), isNull(), any())).thenReturn(new ChatMessageResponse());
        var item = item(); item.setTurnsJson("[{\"message\":\"x\"},{\"message\":\"y\"}]");
        item.setExpectedV2OutcomesJson("[{\"turn\":2,\"assertions\":{\"verificationStatus\":\"NOT_EXECUTED\",\"hardConstraintsVerified\":false}}]");
        var result = evaluate(item);
        assertEquals(true, result.getV2OutcomeMatched(), result.getTurnOutputsJson());
    }

    @Test void wrongStateAndRelationBothFailFinalCase() {
        when(chat.chat(any(), isNull(), any())).thenReturn(new ChatMessageResponse());
        // 分别只配置一种断言，避免一项失败掩盖另一项没有参与 Case Pass。
        for (boolean stateAssertion : new boolean[]{true, false}) {
            var item = item(); item.setTurnsJson("[{\"message\":\"x\"},{\"message\":\"y\"}]");
            if (stateAssertion) item.setExpectedTurnStatesJson("[{\"turn\":1,\"memory\":{\"candidatePool\":{\"nonEmpty\":true}}}]");
            else item.setExpectedRelationsJson("[{\"fromTurn\":1,\"toTurn\":2,\"type\":\"recommendations\",\"relation\":\"DISJOINT\"}]");
            var result = evaluate(item);
            assertEquals(false, result.getMemoryMatched()); assertFailed(result);
            assertTrue(result.getTurnOutputsJson().contains(stateAssertion ? "nonEmpty" : "DISJOINT"));
        }
    }

    @Test void missingSessionIdentityCannotProveSameSession() {
        when(chat.chat(any(), isNull(), any())).thenReturn(new ChatMessageResponse());
        var item = item(); item.setTurnsJson("[{\"message\":\"x\"},{\"message\":\"y\"}]");
        item.setExpectedRelationsJson("[{\"fromTurn\":1,\"toTurn\":2,\"type\":\"decisionSession\",\"relation\":\"SAME\"}]");
        var result = evaluate(item);
        assertEquals(false, result.getMemoryMatched()); assertFailed(result);
    }

    @Test void infrastructureErrorRemainsFailedEvenWhenOtherFlagsAreTrue() {
        var result = new AiConversationEvaluationCaseResult(); result.setDurationMs(0L);
        result.setLocalityMatched(true); result.setFinalStatusMatched(true); result.setShopMatched(true);
        result.setErrorMessage("snapshot read failed"); assertFailed(result);
    }

    @Test void hardConstraintsVerifiedRequiresExplicitVerifiedPass() {
        var versions = mock(WorkingMemoryVersionService.class);
        var events = mock(com.hmdp.ai.mapper.AiConversationEventMapper.class);
        ReflectionTestUtils.setField(service, "workingMemoryVersionService", versions);
        ReflectionTestUtils.setField(service, "conversationEventMapper", events);
        var row = new com.hmdp.ai.entity.AiWorkingMemory();
        row.setId(1L); row.setVersion(1); row.setMemoryJson("{\"tasks\":[]}");
        when(versions.latest(any())).thenReturn(row);
        when(chat.chat(any(), isNull(), any())).thenReturn(new ChatMessageResponse());
        for (String status : List.of("NOT_EXECUTED", "NOT_APPLICABLE", "VERIFIED_FAIL", "VERIFIED_PASS")) {
            var event = new com.hmdp.ai.entity.AiConversationEvent();
            event.setEventResult("{\"verificationStatus\":\"" + status + "\"}");
            when(events.selectOne(any())).thenReturn(event);
            var item = item();
            item.setExpectedV2OutcomesJson("[{\"turn\":1,\"assertions\":{\"hardConstraintsVerified\":true}}]");
            var result = evaluate(item);
            assertEquals("VERIFIED_PASS".equals(status), result.getV2OutcomeMatched(), status);
            if (!"VERIFIED_PASS".equals(status)) assertFailed(result);
        }
    }

    @Test void semanticEvidenceIsReadableWithoutPersistedMemory() {
        var events = mock(com.hmdp.ai.mapper.AiConversationEventMapper.class);
        ReflectionTestUtils.setField(service, "conversationEventMapper", events);
        var event = new com.hmdp.ai.entity.AiConversationEvent();
        event.setEventResult("{\"semantic\":{\"taskDirective\":\"CONTINUE\"}}");
        when(events.selectList(any())).thenReturn(List.of(event));
        when(chat.chat(any(), isNull(), any())).thenReturn(new ChatMessageResponse());
        var item = item();
        item.setExpectedV2OutcomesJson("[{\"turn\":1,\"assertions\":{\"semantic.taskDirective\":\"CONTINUE\"}}]");
        assertEquals(true, evaluate(item).getV2OutcomeMatched());
    }

    @Test void wrongGroundedIdentityFailsEvenIfEntityBindingIsNonEmpty() throws Exception {
        var type = Class.forName(AiConversationEvaluationService.class.getName() + "$EvaluationTurnSnapshot");
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        Object from = constructor.newInstance(); Object to = constructor.newInstance();
        ReflectionTestUtils.setField(from, "turnNo", 1); ReflectionTestUtils.setField(to, "turnNo", 2);
        ReflectionTestUtils.setField(from, "currentVisibleShopIds", List.of(11L, 22L));
        String expectation = "[{\"fromTurn\":1,\"toTurn\":2,\"type\":\"groundedOrdinal\",\"relation\":\"EQUALS_VISIBLE\",\"ordinal\":1}]";
        for (long bound : new long[]{22L, 11L}) {
            ReflectionTestUtils.setField(to, "groundedEntities", List.of(java.util.Map.of("shopId", bound)));
            var failures = new java.util.ArrayList<java.util.Map<String, Object>>();
            Boolean matched = ReflectionTestUtils.invokeMethod(service, "matchesRelations", expectation, List.of(from, to), failures);
            assertEquals(bound == 11L, matched);
            if (bound != 11L) {
                var result = new AiConversationEvaluationCaseResult(); result.setDurationMs(0L);
                result.setLocalityMatched(true); result.setFinalStatusMatched(true); result.setShopMatched(true);
                result.setMemoryMatched(matched); assertFailed(result); assertEquals(1, failures.size());
            }
        }
    }

    private AiConversationEvaluationCase item() {
        AiConversationEvaluationCase item = new AiConversationEvaluationCase();
        item.setId(1L); item.setCaseCode("INFRA"); item.setTurnsJson("[{\"message\":\"x\"}]");
        return item;
    }
    private AiConversationEvaluationCaseResult evaluate(AiConversationEvaluationCase item) {
        return ReflectionTestUtils.invokeMethod(service, "evaluate", 1L, item);
    }
    private AiConversationEvaluationRun finish(AiConversationEvaluationCaseResult result) {
        AiConversationEvaluationRun run = new AiConversationEvaluationRun();
        ReflectionTestUtils.invokeMethod(service, "finish", run, List.of(result)); return run;
    }
    private void assertFailed(AiConversationEvaluationCaseResult result) {
        AiConversationEvaluationRun run = finish(result);
        assertEquals("COMPLETED_WITH_ERRORS", run.getStatus()); assertEquals(0, run.getCompletedCount());
    }
}
