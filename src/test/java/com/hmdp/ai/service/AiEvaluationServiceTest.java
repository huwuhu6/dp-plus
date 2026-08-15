package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.DecisionMetrics;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.dto.EvaluationRunResponse;
import com.hmdp.ai.dto.EvaluationRunComparisonResponse;
import com.hmdp.ai.entity.AiEvaluationCase;
import com.hmdp.ai.entity.AiEvaluationRun;
import com.hmdp.ai.mapper.AiEvaluationCaseMapper;
import com.hmdp.ai.mapper.AiEvaluationCaseResultMapper;
import com.hmdp.ai.mapper.AiEvaluationRunMapper;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiEvaluationServiceTest {
    @Test
    void aggregatesRecallMrrAndModelMetricsFromCaseResults() {
        AiEvaluationService service = new AiEvaluationService();
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        AiEvaluationCaseMapper caseMapper = mock(AiEvaluationCaseMapper.class);
        AiEvaluationRunMapper runMapper = mock(AiEvaluationRunMapper.class);
        AiEvaluationCaseResultMapper resultMapper = mock(AiEvaluationCaseResultMapper.class);
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        ReflectionTestUtils.setField(service, "caseMapper", caseMapper);
        ReflectionTestUtils.setField(service, "runMapper", runMapper);
        ReflectionTestUtils.setField(service, "caseResultMapper", resultMapper);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());

        AiEvaluationCase firstCase = evaluationCase(1L, "FIRST", "4");
        AiEvaluationCase secondCase = evaluationCase(2L, "SECOND", "6");
        when(caseMapper.selectList(any(QueryWrapper.class))).thenReturn(Arrays.asList(firstCase, secondCase));
        doAnswer(invocation -> {
            AiEvaluationRun run = invocation.getArgument(0);
            run.setId(99L);
            return 1;
        }).when(runMapper).insert(any(AiEvaluationRun.class));
        when(decisionService.decide(any())).thenReturn(response(10L, 4L, 1, 1), response(11L, 8L, 1, 1));

        EvaluationRunResponse response = service.runActiveCases();

        assertEquals("COMPLETED", response.getRun().getStatus());
        assertEquals(2, response.getRun().getCompletedCount());
        assertEquals(2, response.getRun().getRankingEvaluatedCount());
        assertEquals(2, response.getRun().getConstraintMatchedCount());
        assertEquals(2, response.getRun().getModelCallCount());
        assertEquals(2, response.getRun().getModelSuccessCount());
        assertEquals(0, response.getRun().getModelFailureCount());
        assertEquals(1000L, response.getRun().getAvgTotalDurationMs());
        assertEquals(1000L, response.getRun().getP95TotalDurationMs());
        assertEquals(800L, response.getRun().getAvgExtractingDurationMs());
        assertEquals("deepseek-v4-flash", response.getRun().getModel());
        assertEquals("structured-profile-evidence-v2", response.getRun().getRetrievalStrategyVersion());
        assertEquals(0.5D, response.getRun().getRecallAtK());
        assertEquals(0.5D, response.getRun().getMrr());
        assertEquals(1D, response.getCaseResults().get(0).getRecallAtK());
        assertEquals(0D, response.getCaseResults().get(1).getRecallAtK());
        verify(runMapper).updateById(any(AiEvaluationRun.class));
    }

    @Test
    void rejectsReadingAnotherUsersEvaluationRun() {
        AiEvaluationService service = new AiEvaluationService();
        AiEvaluationRunMapper runMapper = mock(AiEvaluationRunMapper.class);
        ReflectionTestUtils.setField(service, "runMapper", runMapper);

        AiEvaluationRun run = new AiEvaluationRun();
        run.setId(99L);
        run.setUserId(1001L);
        when(runMapper.selectById(99L)).thenReturn(run);
        UserHolder.saveUser(user(1002L));
        try {
            assertThrows(IllegalStateException.class, () -> service.getRun(99L));
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void summarizesFailedCasesWithoutLeakingUnderlyingErrorDetails() {
        AiEvaluationService service = new AiEvaluationService();
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        AiEvaluationCaseMapper caseMapper = mock(AiEvaluationCaseMapper.class);
        AiEvaluationRunMapper runMapper = mock(AiEvaluationRunMapper.class);
        AiEvaluationCaseResultMapper resultMapper = mock(AiEvaluationCaseResultMapper.class);
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        ReflectionTestUtils.setField(service, "caseMapper", caseMapper);
        ReflectionTestUtils.setField(service, "runMapper", runMapper);
        ReflectionTestUtils.setField(service, "caseResultMapper", resultMapper);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());

        AiEvaluationCase failedCase = evaluationCase(7L, "FAILED_CASE", "4");
        when(caseMapper.selectList(any(QueryWrapper.class))).thenReturn(Arrays.asList(failedCase));
        doAnswer(invocation -> {
            invocation.<AiEvaluationRun>getArgument(0).setId(100L);
            return 1;
        }).when(runMapper).insert(any(AiEvaluationRun.class));
        when(decisionService.decide(any())).thenThrow(new IllegalStateException("upstream internal detail"));

        EvaluationRunResponse response = service.runActiveCases();

        assertEquals("COMPLETED_WITH_ERRORS", response.getRun().getStatus());
        assertEquals("失败用例数=1，caseId=7", response.getRun().getErrorSummary());
    }

    @Test
    void comparesOnlyRunsUnderTheSameExperimentConditions() {
        AiEvaluationService service = new AiEvaluationService();
        AiEvaluationRunMapper runMapper = mock(AiEvaluationRunMapper.class);
        ReflectionTestUtils.setField(service, "runMapper", runMapper);
        AiEvaluationRun baseline = evaluationRun(1L, 1010L, 14, 13, 0.9D);
        AiEvaluationRun current = evaluationRun(2L, 1010L, 15, 15, 1D);
        when(runMapper.selectById(1L)).thenReturn(baseline);
        when(runMapper.selectById(2L)).thenReturn(current);
        UserHolder.saveUser(user(1010L));
        try {
            EvaluationRunComparisonResponse response = service.compareRuns(2L, 1L);
            assertEquals(0.0667D, response.getMetricDeltas().get("statusMatchRate"));
            assertEquals(0.1D, response.getMetricDeltas().get("mrr"));
            assertEquals(0D, response.getMetricDeltas().get("hardConstraintViolationCount"));
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void comparesDifferentRetrievalStrategiesAsTheExperimentVariable() {
        AiEvaluationService service = new AiEvaluationService();
        AiEvaluationRunMapper runMapper = mock(AiEvaluationRunMapper.class);
        ReflectionTestUtils.setField(service, "runMapper", runMapper);
        AiEvaluationRun baseline = evaluationRun(1L, 1010L, 15, 15, 1D);
        baseline.setRetrievalStrategyVersion("structured-profile-evidence-v1");
        AiEvaluationRun current = evaluationRun(2L, 1010L, 15, 15, 1D);
        when(runMapper.selectById(1L)).thenReturn(baseline);
        when(runMapper.selectById(2L)).thenReturn(current);
        UserHolder.saveUser(user(1010L));
        try {
            EvaluationRunComparisonResponse response = service.compareRuns(2L, 1L);
            assertEquals(true, response.getRetrievalStrategyChanged());
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void rejectsComparisonWhenEvaluationDatasetsDiffer() {
        AiEvaluationService service = new AiEvaluationService();
        AiEvaluationRunMapper runMapper = mock(AiEvaluationRunMapper.class);
        ReflectionTestUtils.setField(service, "runMapper", runMapper);
        AiEvaluationRun baseline = evaluationRun(1L, 1010L, 15, 15, 1D);
        baseline.setEvaluationDatasetVersion("seed-v2");
        AiEvaluationRun current = evaluationRun(2L, 1010L, 15, 15, 1D);
        when(runMapper.selectById(1L)).thenReturn(baseline);
        when(runMapper.selectById(2L)).thenReturn(current);
        UserHolder.saveUser(user(1010L));
        try {
            assertThrows(IllegalArgumentException.class, () -> service.compareRuns(2L, 1L));
        } finally {
            UserHolder.removeUser();
        }
    }

    private AiEvaluationRun evaluationRun(Long id, Long userId, int statusMatchedCount,
                                          int constraintMatchedCount, double mrr) {
        AiEvaluationRun run = new AiEvaluationRun();
        run.setId(id);
        run.setUserId(userId);
        run.setModel("deepseek-v4-flash");
        run.setRetrievalStrategyVersion("structured-profile-evidence-v2");
        run.setEvaluationDatasetVersion("seed-v1");
        run.setCaseCount(15);
        run.setRankingEvaluatedCount(10);
        run.setStatusMatchedCount(statusMatchedCount);
        run.setConstraintMatchedCount(constraintMatchedCount);
        run.setCompletedCount(10);
        run.setModelCallCount(15);
        run.setModelSuccessCount(15);
        run.setModelFailureCount(0);
        run.setHardConstraintViolationCount(0);
        run.setFactualConsistentCount(15);
        run.setRecallAtK(1D);
        run.setMrr(mrr);
        run.setEvidenceCoverageRate(1D);
        return run;
    }

    private UserDTO user(Long id) {
        UserDTO user = new UserDTO();
        user.setId(id);
        return user;
    }

    private AiEvaluationCase evaluationCase(Long id, String code, String expectedShopIds) {
        AiEvaluationCase evaluationCase = new AiEvaluationCase();
        evaluationCase.setId(id);
        evaluationCase.setCaseCode(code);
        evaluationCase.setQueryText("测试查询");
        evaluationCase.setExpectedStatus("COMPLETED");
        evaluationCase.setExpectedShopIds(expectedShopIds);
        evaluationCase.setMaxCandidates(3);
        return evaluationCase;
    }

    private DecisionResponse response(Long sessionId, Long shopId, int modelCalls, int modelSuccesses) {
        DecisionRecommendation recommendation = new DecisionRecommendation();
        recommendation.setShopId(shopId);
        DecisionMetrics metrics = new DecisionMetrics();
        metrics.setModelCallCount(modelCalls);
        metrics.setModelSuccessCount(modelSuccesses);
        metrics.setModelFailureCount(modelCalls - modelSuccesses);
        metrics.setEvidenceCoverageRate(1D);
        metrics.setTotalDurationMs(1000L);
        metrics.setExtractingDurationMs(800L);
        DecisionResponse response = new DecisionResponse();
        response.setSessionId(sessionId);
        response.setStatus("COMPLETED");
        response.setMetrics(metrics);
        response.setConstraints(new DecisionConstraints());
        response.getRecommendations().add(recommendation);
        return response;
    }
}
