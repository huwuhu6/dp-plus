package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.ChatMessageResponse;
import com.hmdp.ai.dto.ContextRewriteResult;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.dto.ConversationEvaluationRunResponse;
import com.hmdp.ai.dto.ConversationEvaluationRunComparisonResponse;
import com.hmdp.ai.dto.ConversationWorkingMemory;
import com.hmdp.ai.entity.AiConversationEvaluationCase;
import com.hmdp.ai.entity.AiConversationEvaluationRun;
import com.hmdp.ai.entity.AiConversationEvaluationCaseResult;
import com.hmdp.ai.dto.ConversationEvaluationDiagnosticsResponse;
import com.hmdp.ai.entity.AiAgentToolCall;
import com.hmdp.ai.entity.AiChatSession;
import com.hmdp.ai.mapper.AiConversationEvaluationCaseMapper;
import com.hmdp.ai.mapper.AiConversationEvaluationCaseResultMapper;
import com.hmdp.ai.mapper.AiConversationEvaluationRunMapper;
import com.hmdp.ai.mapper.AiAgentToolCallMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiConversationEvaluationServiceTest {
    @Test
    void evaluatesTurnStateToolsAndRelationsWithoutChangingLegacyAssertions() throws Exception {
        AiConversationEvaluationService service = new AiConversationEvaluationService();
        ChatOrchestrationService chatService = mock(ChatOrchestrationService.class);
        ConversationEvaluationDatasetLoader datasetLoader = mock(ConversationEvaluationDatasetLoader.class);
        AiConversationEvaluationRunMapper runMapper = mock(AiConversationEvaluationRunMapper.class);
        AiConversationEvaluationCaseResultMapper resultMapper = mock(AiConversationEvaluationCaseResultMapper.class);
        AiAgentToolCallMapper toolCallMapper = mock(AiAgentToolCallMapper.class);
        ConversationStateService stateService = mock(ConversationStateService.class);
        ReflectionTestUtils.setField(service, "chatOrchestrationService", chatService);
        ReflectionTestUtils.setField(service, "datasetLoader", datasetLoader);
        ReflectionTestUtils.setField(service, "runMapper", runMapper);
        ReflectionTestUtils.setField(service, "resultMapper", resultMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(service, "conversationStateService", stateService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());

        AiConversationEvaluationCase evaluationCase = new AiConversationEvaluationCase();
        evaluationCase.setId(91L); evaluationCase.setCaseCode("TURN_ASSERTIONS");
        evaluationCase.setTurnsJson("[{\"message\":\"one\"},{\"message\":\"two\"},{\"message\":\"three\"},{\"message\":\"four\"}]");
        evaluationCase.setExpectedRoutesJson("[\"START_DECISION\",\"START_DECISION\",\"START_DECISION\",\"BUSINESS_FOLLOW_UP\"]");
        evaluationCase.setExpectedToolNamesJson("[\"get_shop_detail\"]");
        evaluationCase.setExpectedTurnStatesJson("[{\"turn\":1,\"memory\":{\"activeCriteria.cuisine\":{\"equals\":\"火锅\"},\"candidatePool\":{\"size\":1},\"shownShopIds\":{\"contains\":1},\"focusedShopId\":{\"equals\":1}}},{\"turn\":2,\"memory\":{\"focusedShopId\":{\"null\":true},\"candidatePool\":{\"empty\":true},\"activeCriteria.nonexistent\":{\"absent\":true}}}]");
        evaluationCase.setExpectedToolsByTurnJson("[{\"turn\":3,\"tools\":[{\"name\":\"get_shop_detail\",\"arguments\":{\"shopId\":2},\"candidateFrom\":{\"turn\":3,\"ordinal\":1}}]}]");
        evaluationCase.setExpectedRelationsJson("[{\"type\":\"candidatePool\",\"relation\":\"INVALIDATED\",\"fromTurn\":1,\"toTurn\":2},{\"type\":\"candidatePool\",\"relation\":\"PRESERVED\",\"fromTurn\":3,\"toTurn\":4},{\"type\":\"recommendations\",\"relation\":\"DISJOINT\",\"fromTurn\":1,\"toTurn\":3},{\"type\":\"focusedShop\",\"relation\":\"CHANGED\",\"fromTurn\":1,\"toTurn\":3},{\"type\":\"focusedShop\",\"relation\":\"PRESERVED\",\"fromTurn\":3,\"toTurn\":4},{\"type\":\"decisionSession\",\"relation\":\"CHANGED\",\"fromTurn\":1,\"toTurn\":3}]");
        when(datasetLoader.loadCases(any())).thenReturn(Collections.singletonList(evaluationCase));
        doAnswer(invocation -> { invocation.<AiConversationEvaluationRun>getArgument(0).setId(91L); return 1; })
                .when(runMapper).insert(any(AiConversationEvaluationRun.class));
        when(chatService.chat(any(), isNull(), any())).thenReturn(
                responseWithRecommendationIds("START_DECISION", "COMPLETED", 1L),
                responseWithRecommendationIds("START_DECISION", "WAITING_RELAXATION"),
                responseWithRecommendationIds("START_DECISION", "COMPLETED", 2L),
                response("BUSINESS_FOLLOW_UP", "COMPLETED"));
        AiChatSession session = new AiChatSession(); session.setVersion(4);
        when(stateService.getOrCreate(any())).thenReturn(session);
        when(stateService.workingMemory(session)).thenReturn(
                memory("火锅", list(1L), list(1L), 1L, 10L),
                memory("火锅", Collections.emptyList(), Collections.emptyList(), null, 10L),
                memory("日料", list(2L), list(2L), 2L, 11L),
                memory("日料", list(2L), list(2L), 2L, 11L));
        AiAgentToolCall call = new AiAgentToolCall();
        call.setToolName("get_shop_detail"); call.setToolInputJson("{\"shopId\":2,\"includeHours\":true}"); call.setTurnNo(3);
        when(toolCallMapper.selectList(any())).thenReturn(Collections.singletonList(call));

        ConversationEvaluationRunResponse response = service.runActiveCases();

        AiConversationEvaluationCaseResult result = response.getCaseResults().get(0);
        assertEquals(true, result.getMemoryMatched(), result.getTurnOutputsJson());
        assertEquals(true, result.getToolMatched());
        assertEquals(true, result.getTurnOutputsJson().contains("assertionFailures"));
    }

    @Test
    void keepsLegacyCaseBehaviorWhenTurnAssertionsAreAbsent() {
        AiConversationEvaluationCase evaluationCase = new AiConversationEvaluationCase();
        assertEquals(null, evaluationCase.getExpectedTurnStatesJson());
        assertEquals(null, evaluationCase.getExpectedToolsByTurnJson());
        assertEquals(null, evaluationCase.getExpectedRelationsJson());
    }
    @Test
    void acceptsDeclaredSafeTerminalStatusAlternatives() {
        AiConversationEvaluationService service = new AiConversationEvaluationService();

        assertEquals(true, ReflectionTestUtils.invokeMethod(service, "equalsExpected",
                "COMPLETED|WAITING_RELAXATION", "COMPLETED"));
        assertEquals(true, ReflectionTestUtils.invokeMethod(service, "equalsExpected",
                "COMPLETED|WAITING_RELAXATION", "WAITING_RELAXATION"));
        assertEquals(false, ReflectionTestUtils.invokeMethod(service, "equalsExpected",
                "COMPLETED|WAITING_RELAXATION", "FAILED"));
    }

    @Test
    void runsScriptedTurnsThroughChatEntryAndAggregatesRouteMetrics() {
        AiConversationEvaluationService service = new AiConversationEvaluationService();
        ChatOrchestrationService chatService = mock(ChatOrchestrationService.class);
        AiConversationEvaluationCaseMapper caseMapper = mock(AiConversationEvaluationCaseMapper.class);
        ConversationEvaluationDatasetLoader datasetLoader = mock(ConversationEvaluationDatasetLoader.class);
        AiConversationEvaluationRunMapper runMapper = mock(AiConversationEvaluationRunMapper.class);
        AiConversationEvaluationCaseResultMapper resultMapper = mock(AiConversationEvaluationCaseResultMapper.class);
        AiAgentToolCallMapper toolCallMapper = mock(AiAgentToolCallMapper.class);
        ReflectionTestUtils.setField(service, "chatOrchestrationService", chatService);
        ReflectionTestUtils.setField(service, "caseMapper", caseMapper);
        ReflectionTestUtils.setField(service, "datasetLoader", datasetLoader);
        ReflectionTestUtils.setField(service, "runMapper", runMapper);
        ReflectionTestUtils.setField(service, "resultMapper", resultMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());

        AiConversationEvaluationCase evaluationCase = new AiConversationEvaluationCase();
        evaluationCase.setId(1L);
        evaluationCase.setCaseCode("FOLLOW_UP");
        evaluationCase.setTurnsJson("[{\"message\":\"找日料\"},{\"message\":\"这家评价如何\"}]");
        evaluationCase.setExpectedRoutesJson("[\"START_DECISION\",\"BUSINESS_FOLLOW_UP\"]");
        evaluationCase.setExpectedFinalStatus("COMPLETED");
        evaluationCase.setExpectedToolNamesJson("[\"search_shop_evidence\"]");
        when(datasetLoader.loadCases(any())).thenReturn(Collections.singletonList(evaluationCase));
        doAnswer(invocation -> { invocation.<AiConversationEvaluationRun>getArgument(0).setId(12L); return 1; })
                .when(runMapper).insert(any(AiConversationEvaluationRun.class));
        when(chatService.chat(any(), isNull(), any())).thenReturn(response("START_DECISION", "COMPLETED"), response("BUSINESS_FOLLOW_UP", "COMPLETED"));
        AiAgentToolCall toolCall = new AiAgentToolCall();
        toolCall.setToolName("search_shop_evidence");
        when(toolCallMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.singletonList(toolCall));

        ConversationEvaluationRunResponse response = service.runActiveCases();

        assertEquals("COMPLETED", response.getRun().getStatus());
        assertEquals(1, response.getRun().getRouteMatchedCount());
        assertEquals(1, response.getRun().getToolMatchedCount());
        assertEquals(1, response.getRun().getFinalStatusMatchedCount());
        assertEquals(1, response.getRun().getCompletedCount());
        assertEquals(true, response.getCaseResults().get(0).getRouteMatched());
        assertEquals(0, response.getRun().getContextRewriteExpectedCount());
        assertEquals(true, response.getCaseResults().get(0).getChatId().matches("[A-Za-z0-9-]{1,64}"));
    }

    @Test
    void verifiesAlternativeRecommendationsDoNotReusePreviouslyShownShops() {
        AiConversationEvaluationService service = new AiConversationEvaluationService();
        ChatOrchestrationService chatService = mock(ChatOrchestrationService.class);
        AiConversationEvaluationCaseMapper caseMapper = mock(AiConversationEvaluationCaseMapper.class);
        ConversationEvaluationDatasetLoader datasetLoader = mock(ConversationEvaluationDatasetLoader.class);
        AiConversationEvaluationRunMapper runMapper = mock(AiConversationEvaluationRunMapper.class);
        AiConversationEvaluationCaseResultMapper resultMapper = mock(AiConversationEvaluationCaseResultMapper.class);
        AiAgentToolCallMapper toolCallMapper = mock(AiAgentToolCallMapper.class);
        ReflectionTestUtils.setField(service, "chatOrchestrationService", chatService);
        ReflectionTestUtils.setField(service, "caseMapper", caseMapper);
        ReflectionTestUtils.setField(service, "datasetLoader", datasetLoader);
        ReflectionTestUtils.setField(service, "runMapper", runMapper);
        ReflectionTestUtils.setField(service, "resultMapper", resultMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());

        AiConversationEvaluationCase evaluationCase = new AiConversationEvaluationCase();
        evaluationCase.setId(5L);
        evaluationCase.setCaseCode("ALTERNATIVE_RECOMMENDATIONS_ARE_UNSEEN");
        evaluationCase.setTurnsJson("[{\"message\":\"推荐几家\"},{\"message\":\"换几家\"}]");
        evaluationCase.setExpectedRoutesJson("[\"START_DECISION\",\"START_DECISION\"]");
        evaluationCase.setExpectedToolNamesJson("[]");
        evaluationCase.setExpectedFinalStatus("COMPLETED");
        evaluationCase.setExpectedUnseenFromTurn(1);
        when(datasetLoader.loadCases(any())).thenReturn(Collections.singletonList(evaluationCase));
        doAnswer(invocation -> { invocation.<AiConversationEvaluationRun>getArgument(0).setId(16L); return 1; })
                .when(runMapper).insert(any(AiConversationEvaluationRun.class));
        when(chatService.chat(any(), isNull(), any())).thenReturn(
                responseWithRecommendationIds("START_DECISION", "COMPLETED", 74L, 89L),
                responseWithRecommendationIds("START_DECISION", "COMPLETED", 85L, 92L));
        when(toolCallMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        ConversationEvaluationRunResponse response = service.runActiveCases();

        assertEquals("COMPLETED", response.getRun().getStatus());
        assertEquals(true, response.getCaseResults().get(0).getUnseenRecommendationsMatched());
        assertEquals(1, response.getRun().getUnseenRecommendationExpectedCount());
        assertEquals(1, response.getRun().getUnseenRecommendationMatchedCount());
    }

    @Test
    void marksAlternativeRecommendationEvaluationAsFailedWhenAShopIsRepeated() {
        AiConversationEvaluationService service = new AiConversationEvaluationService();
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        DecisionRecommendation first = recommendation(74L);
        DecisionRecommendation repeated = recommendation(74L);

        assertEquals(false, ReflectionTestUtils.invokeMethod(service, "matchesUnseenRecommendations", 1,
                List.of(List.of(first), List.of(repeated))));
    }

    @Test
    void marksAlternativeRecommendationEvaluationAsFailedWhenNoReplacementIsReturned() {
        AiConversationEvaluationService service = new AiConversationEvaluationService();
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        assertEquals(false, ReflectionTestUtils.invokeMethod(service, "matchesUnseenRecommendations", 1,
                List.of(List.of(recommendation(74L)), Collections.emptyList())));
    }

    @Test
    void verifiesEachConfiguredAlternativeRecommendationTurnPair() {
        AiConversationEvaluationService service = new AiConversationEvaluationService();
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        List<List<DecisionRecommendation>> snapshots = List.of(
                List.of(recommendation(74L)), List.of(recommendation(85L)), List.of(recommendation(74L)));

        assertEquals(false, ReflectionTestUtils.invokeMethod(service, "matchesUnseenRecommendations", null,
                "[[1,2],[2,3],[1,3]]", snapshots));
    }

    @Test
    void submitsConversationEvaluationWithoutHoldingTheRequestForAllTurns() {
        AiConversationEvaluationService service = new AiConversationEvaluationService();
        ChatOrchestrationService chatService = mock(ChatOrchestrationService.class);
        AiConversationEvaluationCaseMapper caseMapper = mock(AiConversationEvaluationCaseMapper.class);
        ConversationEvaluationDatasetLoader datasetLoader = mock(ConversationEvaluationDatasetLoader.class);
        AiConversationEvaluationRunMapper runMapper = mock(AiConversationEvaluationRunMapper.class);
        AiConversationEvaluationCaseResultMapper resultMapper = mock(AiConversationEvaluationCaseResultMapper.class);
        AiAgentToolCallMapper toolCallMapper = mock(AiAgentToolCallMapper.class);
        ReflectionTestUtils.setField(service, "chatOrchestrationService", chatService);
        ReflectionTestUtils.setField(service, "caseMapper", caseMapper);
        ReflectionTestUtils.setField(service, "datasetLoader", datasetLoader);
        ReflectionTestUtils.setField(service, "runMapper", runMapper);
        ReflectionTestUtils.setField(service, "resultMapper", resultMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        // Execute inline so this test can assert the state transition deterministically.
        ReflectionTestUtils.setField(service, "evaluationExecutor", (Executor) Runnable::run);

        AiConversationEvaluationCase evaluationCase = new AiConversationEvaluationCase();
        evaluationCase.setId(3L);
        evaluationCase.setCaseCode("ASYNC_SUBMIT");
        evaluationCase.setTurnsJson("[{\"message\":\"找日料\"}]");
        evaluationCase.setExpectedRoutesJson("[\"START_DECISION\"]");
        evaluationCase.setExpectedToolNamesJson("[]");
        evaluationCase.setExpectedFinalStatus("COMPLETED");
        when(datasetLoader.loadCases(any())).thenReturn(Collections.singletonList(evaluationCase));
        doAnswer(invocation -> { invocation.<AiConversationEvaluationRun>getArgument(0).setId(14L); return 1; })
                .when(runMapper).insert(any(AiConversationEvaluationRun.class));
        when(chatService.chat(any(), isNull(), any())).thenReturn(response("START_DECISION", "COMPLETED"));
        when(toolCallMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        ConversationEvaluationRunResponse response = service.submitActiveCases();

        assertEquals(14L, response.getRun().getId());
        assertEquals("COMPLETED", response.getRun().getStatus());
        assertEquals(0, response.getCaseResults().size());
        org.mockito.Mockito.verify(runMapper, org.mockito.Mockito.atLeastOnce()).updateById(response.getRun());
    }

    @Test
    void measuresContextRewriteAgainstStableBusinessAssertions() {
        AiConversationEvaluationService service = new AiConversationEvaluationService();
        ChatOrchestrationService chatService = mock(ChatOrchestrationService.class);
        AiConversationEvaluationCaseMapper caseMapper = mock(AiConversationEvaluationCaseMapper.class);
        ConversationEvaluationDatasetLoader datasetLoader = mock(ConversationEvaluationDatasetLoader.class);
        AiConversationEvaluationRunMapper runMapper = mock(AiConversationEvaluationRunMapper.class);
        AiConversationEvaluationCaseResultMapper resultMapper = mock(AiConversationEvaluationCaseResultMapper.class);
        AiAgentToolCallMapper toolCallMapper = mock(AiAgentToolCallMapper.class);
        ReflectionTestUtils.setField(service, "chatOrchestrationService", chatService);
        ReflectionTestUtils.setField(service, "caseMapper", caseMapper);
        ReflectionTestUtils.setField(service, "datasetLoader", datasetLoader);
        ReflectionTestUtils.setField(service, "runMapper", runMapper);
        ReflectionTestUtils.setField(service, "resultMapper", resultMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());

        AiConversationEvaluationCase evaluationCase = new AiConversationEvaluationCase();
        evaluationCase.setId(2L);
        evaluationCase.setCaseCode("CONTEXT_REWRITE");
        evaluationCase.setTurnsJson("[{\"message\":\"推荐附近日料\"},{\"message\":\"第二家怎么样？\"}]");
        evaluationCase.setExpectedRoutesJson("[\"START_DECISION\",\"BUSINESS_FOLLOW_UP\"]");
        evaluationCase.setExpectedContextRewritesJson("[null,{\"applied\":true,\"candidateOrdinal\":2}]");
        evaluationCase.setExpectedToolNamesJson("[]");
        when(datasetLoader.loadCases(any())).thenReturn(Collections.singletonList(evaluationCase));
        doAnswer(invocation -> { invocation.<AiConversationEvaluationRun>getArgument(0).setId(13L); return 1; })
                .when(runMapper).insert(any(AiConversationEvaluationRun.class));
        when(chatService.chat(any(), isNull(), any())).thenReturn(responseWithCandidates("START_DECISION", "COMPLETED", "第一家", "筑地日本料理（上街店）"),
                responseWithRewrite("BUSINESS_FOLLOW_UP", "COMPLETED", "第二家怎么样？", "查询筑地日本料理（上街店）怎么样？"));
        when(toolCallMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        ConversationEvaluationRunResponse response = service.runActiveCases();

        assertEquals(1, response.getRun().getContextRewriteExpectedCount());
        assertEquals(1, response.getRun().getContextRewriteMatchedCount());
        assertEquals(true, response.getCaseResults().get(0).getContextRewriteMatched());
        assertEquals(1, response.getCaseResults().get(0).getExpectedContextRewriteCount());
        assertEquals(1, response.getCaseResults().get(0).getMatchedContextRewriteCount());
        assertEquals(true, response.getCaseResults().get(0).getActualContextRewritesJson().contains("筑地日本料理"));
    }

    @Test
    void continuesAfterExpectedTurnErrorAndAssertsRecoveryRoute() {
        AiConversationEvaluationService service = new AiConversationEvaluationService();
        ChatOrchestrationService chatService = mock(ChatOrchestrationService.class);
        AiConversationEvaluationCaseMapper caseMapper = mock(AiConversationEvaluationCaseMapper.class);
        ConversationEvaluationDatasetLoader datasetLoader = mock(ConversationEvaluationDatasetLoader.class);
        AiConversationEvaluationRunMapper runMapper = mock(AiConversationEvaluationRunMapper.class);
        AiConversationEvaluationCaseResultMapper resultMapper = mock(AiConversationEvaluationCaseResultMapper.class);
        AiAgentToolCallMapper toolCallMapper = mock(AiAgentToolCallMapper.class);
        ReflectionTestUtils.setField(service, "chatOrchestrationService", chatService);
        ReflectionTestUtils.setField(service, "caseMapper", caseMapper);
        ReflectionTestUtils.setField(service, "datasetLoader", datasetLoader);
        ReflectionTestUtils.setField(service, "runMapper", runMapper);
        ReflectionTestUtils.setField(service, "resultMapper", resultMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());

        AiConversationEvaluationCase evaluationCase = new AiConversationEvaluationCase();
        evaluationCase.setId(4L);
        evaluationCase.setCaseCode("RECOVERY_AFTER_ERROR");
        evaluationCase.setTurnsJson("[{\"message\":\"bad action\"},{\"message\":\"recover\"}]");
        evaluationCase.setExpectedRoutesJson("[\"ERROR\",\"START_DECISION\"]");
        evaluationCase.setExpectedErrorCount(1);
        evaluationCase.setExpectedRecoveryRoutesJson("[\"START_DECISION\"]");
        evaluationCase.setExpectedToolNamesJson("[]");
        evaluationCase.setExpectedFinalStatus("COMPLETED");
        when(datasetLoader.loadCases(any())).thenReturn(Collections.singletonList(evaluationCase));
        doAnswer(invocation -> { invocation.<AiConversationEvaluationRun>getArgument(0).setId(15L); return 1; })
                .when(runMapper).insert(any(AiConversationEvaluationRun.class));
        when(chatService.chat(any(), isNull(), any())).thenThrow(new IllegalArgumentException("invalid option"))
                .thenReturn(response("START_DECISION", "COMPLETED"));
        when(toolCallMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        ConversationEvaluationRunResponse response = service.runActiveCases();

        assertEquals("COMPLETED", response.getRun().getStatus());
        assertEquals(1, response.getCaseResults().get(0).getActualErrorCount());
        assertEquals(true, response.getCaseResults().get(0).getRecoveryMatched());
        assertEquals("[\"ERROR\",\"START_DECISION\"]", response.getCaseResults().get(0).getActualRoutesJson());
    }

    @Test
    void comparesConversationRunsOnlyWithinTheSameDataset() {
        AiConversationEvaluationService service = new AiConversationEvaluationService();
        AiConversationEvaluationRunMapper runMapper = mock(AiConversationEvaluationRunMapper.class);
        ReflectionTestUtils.setField(service, "runMapper", runMapper);
        AiConversationEvaluationRun baseline = run(1L, 100L, "conversation-v1", 10, 8, 7, 9, 8, 1000L);
        AiConversationEvaluationRun current = run(2L, 100L, "conversation-v1", 10, 9, 8, 10, 9, 800L);
        baseline.setContextRewriteExpectedCount(2);
        baseline.setContextRewriteMatchedCount(1);
        current.setContextRewriteExpectedCount(2);
        current.setContextRewriteMatchedCount(2);
        baseline.setUnseenRecommendationExpectedCount(2);
        baseline.setUnseenRecommendationMatchedCount(1);
        current.setUnseenRecommendationExpectedCount(2);
        current.setUnseenRecommendationMatchedCount(2);
        when(runMapper.selectById(1L)).thenReturn(baseline);
        when(runMapper.selectById(2L)).thenReturn(current);
        UserDTO user = new UserDTO();
        user.setId(100L);
        UserHolder.saveUser(user);
        try {
            ConversationEvaluationRunComparisonResponse response = service.compareRuns(2L, 1L);
            assertEquals(0.1D, response.getMetricDeltas().get("routeMatchRate"));
            assertEquals(0.5D, response.getMetricDeltas().get("contextRewriteMatchRate"));
            assertEquals(0.5D, response.getMetricDeltas().get("unseenRecommendationMatchRate"));
            assertEquals(-200D, response.getMetricDeltas().get("avgDurationMs"));
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void rejectsComparisonAcrossDifferentConversationDatasets() {
        AiConversationEvaluationService service = new AiConversationEvaluationService();
        AiConversationEvaluationRunMapper runMapper = mock(AiConversationEvaluationRunMapper.class);
        ReflectionTestUtils.setField(service, "runMapper", runMapper);
        when(runMapper.selectById(1L)).thenReturn(run(1L, 100L, "conversation-v1", 10, 1, 1, 1, 1, 1L));
        when(runMapper.selectById(2L)).thenReturn(run(2L, 100L, "conversation-holdout-v1", 10, 1, 1, 1, 1, 1L));
        UserDTO user = new UserDTO();
        user.setId(100L);
        UserHolder.saveUser(user);
        try {
            assertThrows(IllegalArgumentException.class, () -> service.compareRuns(2L, 1L));
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void returnsOnlyFailedConversationCasesAsDiagnostics() {
        AiConversationEvaluationService service = new AiConversationEvaluationService();
        AiConversationEvaluationRunMapper runMapper = mock(AiConversationEvaluationRunMapper.class);
        AiConversationEvaluationCaseMapper caseMapper = mock(AiConversationEvaluationCaseMapper.class);
        ConversationEvaluationDatasetLoader datasetLoader = mock(ConversationEvaluationDatasetLoader.class);
        AiConversationEvaluationCaseResultMapper resultMapper = mock(AiConversationEvaluationCaseResultMapper.class);
        ReflectionTestUtils.setField(service, "runMapper", runMapper);
        ReflectionTestUtils.setField(service, "caseMapper", caseMapper);
        ReflectionTestUtils.setField(service, "datasetLoader", datasetLoader);
        ReflectionTestUtils.setField(service, "resultMapper", resultMapper);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        when(runMapper.selectById(1L)).thenReturn(run(1L, 100L, "conversation-v1", 2, 2, 1, 2, 2, 100L));
        AiConversationEvaluationCaseResult passed = caseResult(1L, true, true);
        AiConversationEvaluationCaseResult failed = caseResult(2L, true, false);
        failed.setTurnOutputsJson("{\"assertionFailures\":[{\"turnNo\":2,\"path\":\"activeCriteria.cuisine\",\"assertionType\":\"equals\",\"expected\":\"日料\",\"actual\":\"火锅\"}]}");
        when(resultMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(passed, failed));
        AiConversationEvaluationCase evaluationCase = new AiConversationEvaluationCase();
        evaluationCase.setId(2L);
        evaluationCase.setCaseCode("TOOL_COVERAGE_MISS");
        when(datasetLoader.loadCases("conversation-v1")).thenReturn(List.of(evaluationCase));
        UserDTO user = new UserDTO();
        user.setId(100L);
        UserHolder.saveUser(user);
        try {
            ConversationEvaluationDiagnosticsResponse response = service.getDiagnostics(1L);
            assertEquals(1, response.getFailures().size());
            assertEquals("TOOL_COVERAGE_MISS", response.getFailures().get(0).getCaseCode());
            assertEquals(2, response.getFailures().get(0).getTurnAssertionFailures().get(0).get("turnNo"));
            assertEquals(1, response.getFailureCounts().get("toolCoverage"));
            assertEquals(0, response.getFailureCounts().get("route"));
        } finally {
            UserHolder.removeUser();
        }
    }

    private AiConversationEvaluationRun run(Long id, Long userId, String dataset, int caseCount,
                                            int routes, int tools, int locality, int finalStatus, long duration) {
        AiConversationEvaluationRun run = new AiConversationEvaluationRun();
        run.setId(id);
        run.setUserId(userId);
        run.setDatasetVersion(dataset);
        run.setCaseCount(caseCount);
        run.setRouteMatchedCount(routes);
        run.setToolMatchedCount(tools);
        run.setLocalityMatchedCount(locality);
        run.setFinalStatusMatchedCount(finalStatus);
        run.setCompletedCount(finalStatus);
        run.setAvgDurationMs(duration);
        return run;
    }

    private ChatMessageResponse response(String route, String status) {
        ChatMessageResponse response = new ChatMessageResponse();
        response.setRoute(route);
        response.setDecisionStatus(status);
        response.setDecisionSessionId(22L);
        response.setAnswer("ok");
        return response;
    }

    private ChatMessageResponse responseWithRewrite(String route, String status, String original, String rewritten) {
        ChatMessageResponse response = response(route, status);
        ContextRewriteResult rewrite = new ContextRewriteResult();
        rewrite.setOriginalQuery(original);
        rewrite.setRewrittenQuery(rewritten);
        rewrite.setApplied(true);
        rewrite.setUsedModel(true);
        rewrite.setReason("REWRITTEN");
        response.setContextRewrite(rewrite);
        return response;
    }

    private ChatMessageResponse responseWithCandidates(String route, String status, String... names) {
        ChatMessageResponse response = response(route, status);
        com.hmdp.ai.dto.DecisionResponse decision = new com.hmdp.ai.dto.DecisionResponse();
        decision.setStatus(status);
        for (int index = 0; index < names.length; index++) {
            DecisionRecommendation recommendation = new DecisionRecommendation();
            recommendation.setShopId((long) index + 1);
            recommendation.setShopName(names[index]);
            decision.getRecommendations().add(recommendation);
        }
        response.setDecision(decision);
        return response;
    }

    private ChatMessageResponse responseWithRecommendationIds(String route, String status, Long... shopIds) {
        ChatMessageResponse response = response(route, status);
        com.hmdp.ai.dto.DecisionResponse decision = new com.hmdp.ai.dto.DecisionResponse();
        decision.setStatus(status);
        for (Long shopId : shopIds) decision.getRecommendations().add(recommendation(shopId));
        response.setDecision(decision);
        return response;
    }

    private DecisionRecommendation recommendation(Long shopId) {
        DecisionRecommendation recommendation = new DecisionRecommendation();
        recommendation.setShopId(shopId);
        recommendation.setShopName("shop-" + shopId);
        return recommendation;
    }

    private ConversationWorkingMemory memory(String cuisine, List<Long> candidates, List<Long> shown,
                                             Long focusedShopId, Long sessionId) {
        ConversationWorkingMemory memory = new ConversationWorkingMemory();
        memory.getActiveCriteria().setCuisine(cuisine);
        memory.setCandidatePool(new ArrayList<DecisionRecommendation>());
        for (Long candidate : candidates) memory.getCandidatePool().add(recommendation(candidate));
        memory.setShownShopIds(new ArrayList<Long>(shown));
        memory.setFocusedShopId(focusedShopId);
        memory.setActiveDecisionSessionId(sessionId);
        memory.setSourceDecisionSessionId(sessionId);
        return memory;
    }

    private List<Long> list(Long value) {
        return Collections.singletonList(value);
    }

    private AiConversationEvaluationCaseResult caseResult(Long caseId, boolean routeMatched, boolean toolMatched) {
        AiConversationEvaluationCaseResult result = new AiConversationEvaluationCaseResult();
        result.setCaseId(caseId);
        result.setRouteMatched(routeMatched);
        result.setContextRewriteMatched(true);
        result.setToolMatched(toolMatched);
        result.setLocalityMatched(true);
        result.setFinalStatusMatched(true);
        result.setShopMatched(true);
        return result;
    }
}
