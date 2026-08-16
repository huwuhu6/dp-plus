package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.ChatMessageResponse;
import com.hmdp.ai.dto.ConversationEvaluationRunResponse;
import com.hmdp.ai.dto.ConversationEvaluationRunComparisonResponse;
import com.hmdp.ai.entity.AiConversationEvaluationCase;
import com.hmdp.ai.entity.AiConversationEvaluationRun;
import com.hmdp.ai.entity.AiAgentToolCall;
import com.hmdp.ai.mapper.AiConversationEvaluationCaseMapper;
import com.hmdp.ai.mapper.AiConversationEvaluationCaseResultMapper;
import com.hmdp.ai.mapper.AiConversationEvaluationRunMapper;
import com.hmdp.ai.mapper.AiAgentToolCallMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiConversationEvaluationServiceTest {
    @Test
    void runsScriptedTurnsThroughChatEntryAndAggregatesRouteMetrics() {
        AiConversationEvaluationService service = new AiConversationEvaluationService();
        ChatOrchestrationService chatService = mock(ChatOrchestrationService.class);
        AiConversationEvaluationCaseMapper caseMapper = mock(AiConversationEvaluationCaseMapper.class);
        AiConversationEvaluationRunMapper runMapper = mock(AiConversationEvaluationRunMapper.class);
        AiConversationEvaluationCaseResultMapper resultMapper = mock(AiConversationEvaluationCaseResultMapper.class);
        AiAgentToolCallMapper toolCallMapper = mock(AiAgentToolCallMapper.class);
        ReflectionTestUtils.setField(service, "chatOrchestrationService", chatService);
        ReflectionTestUtils.setField(service, "caseMapper", caseMapper);
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
        when(caseMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.singletonList(evaluationCase));
        doAnswer(invocation -> { invocation.<AiConversationEvaluationRun>getArgument(0).setId(12L); return 1; })
                .when(runMapper).insert(any(AiConversationEvaluationRun.class));
        when(chatService.chat(any())).thenReturn(response("START_DECISION", "COMPLETED"), response("BUSINESS_FOLLOW_UP", "COMPLETED"));
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
    }

    @Test
    void comparesConversationRunsOnlyWithinTheSameDataset() {
        AiConversationEvaluationService service = new AiConversationEvaluationService();
        AiConversationEvaluationRunMapper runMapper = mock(AiConversationEvaluationRunMapper.class);
        ReflectionTestUtils.setField(service, "runMapper", runMapper);
        AiConversationEvaluationRun baseline = run(1L, 100L, "conversation-v1", 10, 8, 7, 9, 8, 1000L);
        AiConversationEvaluationRun current = run(2L, 100L, "conversation-v1", 10, 9, 8, 10, 9, 800L);
        when(runMapper.selectById(1L)).thenReturn(baseline);
        when(runMapper.selectById(2L)).thenReturn(current);
        UserDTO user = new UserDTO();
        user.setId(100L);
        UserHolder.saveUser(user);
        try {
            ConversationEvaluationRunComparisonResponse response = service.compareRuns(2L, 1L);
            assertEquals(0.1D, response.getMetricDeltas().get("routeMatchRate"));
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
}
