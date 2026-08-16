package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.ChatMessageResponse;
import com.hmdp.ai.dto.ConversationEvaluationRunResponse;
import com.hmdp.ai.entity.AiConversationEvaluationCase;
import com.hmdp.ai.entity.AiConversationEvaluationRun;
import com.hmdp.ai.mapper.AiConversationEvaluationCaseMapper;
import com.hmdp.ai.mapper.AiConversationEvaluationCaseResultMapper;
import com.hmdp.ai.mapper.AiConversationEvaluationRunMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        ReflectionTestUtils.setField(service, "chatOrchestrationService", chatService);
        ReflectionTestUtils.setField(service, "caseMapper", caseMapper);
        ReflectionTestUtils.setField(service, "runMapper", runMapper);
        ReflectionTestUtils.setField(service, "resultMapper", resultMapper);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());

        AiConversationEvaluationCase evaluationCase = new AiConversationEvaluationCase();
        evaluationCase.setId(1L);
        evaluationCase.setCaseCode("FOLLOW_UP");
        evaluationCase.setTurnsJson("[{\"message\":\"找日料\"},{\"message\":\"这家评价如何\"}]");
        evaluationCase.setExpectedRoutesJson("[\"START_DECISION\",\"BUSINESS_FOLLOW_UP\"]");
        evaluationCase.setExpectedFinalStatus("COMPLETED");
        when(caseMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.singletonList(evaluationCase));
        doAnswer(invocation -> { invocation.<AiConversationEvaluationRun>getArgument(0).setId(12L); return 1; })
                .when(runMapper).insert(any(AiConversationEvaluationRun.class));
        when(chatService.chat(any())).thenReturn(response("START_DECISION", "COMPLETED"), response("BUSINESS_FOLLOW_UP", "COMPLETED"));

        ConversationEvaluationRunResponse response = service.runActiveCases();

        assertEquals("COMPLETED", response.getRun().getStatus());
        assertEquals(1, response.getRun().getRouteMatchedCount());
        assertEquals(1, response.getRun().getFinalStatusMatchedCount());
        assertEquals(1, response.getRun().getCompletedCount());
        assertEquals(true, response.getCaseResults().get(0).getRouteMatched());
    }

    private ChatMessageResponse response(String route, String status) {
        ChatMessageResponse response = new ChatMessageResponse();
        response.setRoute(route);
        response.setDecisionStatus(status);
        response.setAnswer("ok");
        return response;
    }
}
