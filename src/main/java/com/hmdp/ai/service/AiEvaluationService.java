package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.DecisionFollowUpRequest;
import com.hmdp.ai.dto.DecisionMetrics;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.dto.DecisionRequest;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.dto.EvaluationRunResponse;
import com.hmdp.ai.dto.EvaluationRunComparisonResponse;
import com.hmdp.ai.entity.AiEvaluationCase;
import com.hmdp.ai.entity.AiEvaluationCaseResult;
import com.hmdp.ai.entity.AiEvaluationRun;
import com.hmdp.ai.mapper.AiEvaluationCaseMapper;
import com.hmdp.ai.mapper.AiEvaluationCaseResultMapper;
import com.hmdp.ai.mapper.AiEvaluationRunMapper;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.stream.Collectors;

@Service
public class AiEvaluationService {
    @Resource private ConsumptionDecisionService decisionService;
    @Resource private AiEvaluationCaseMapper caseMapper;
    @Resource private AiEvaluationRunMapper runMapper;
    @Resource private AiEvaluationCaseResultMapper caseResultMapper;
    @Resource private ObjectMapper objectMapper;
    @Resource private AiProperties aiProperties;

    public EvaluationRunResponse runActiveCases() {
        return runCases(aiProperties.getEvaluationDatasetVersion());
    }

    public EvaluationRunResponse runHoldoutCases() {
        return runCases(aiProperties.getHoldoutDatasetVersion());
    }

    private EvaluationRunResponse runCases(String datasetVersion) {
        List<AiEvaluationCase> cases = caseMapper.selectList(new QueryWrapper<AiEvaluationCase>()
                .eq("active", true).eq("dataset_version", datasetVersion).orderByAsc("id"));
        if (cases.isEmpty()) throw new IllegalStateException("没有启用的评测用例");

        AiEvaluationRun run = new AiEvaluationRun();
        run.setUserId(UserHolder.getUser() == null ? null : UserHolder.getUser().getId());
        run.setModel(aiProperties.getModel());
        run.setRetrievalStrategyVersion(aiProperties.getRetrievalStrategyVersion());
        run.setEvaluationDatasetVersion(datasetVersion);
        run.setCaseCount(cases.size());
        run.setStatus("RUNNING");
        runMapper.insert(run);

        List<AiEvaluationCaseResult> results = new ArrayList<>();
        for (AiEvaluationCase evaluationCase : cases) {
            AiEvaluationCaseResult result = evaluate(run.getId(), evaluationCase);
            caseResultMapper.insert(result);
            results.add(result);
        }
        finishRun(run, results);
        runMapper.updateById(run);

        EvaluationRunResponse response = new EvaluationRunResponse();
        response.setRun(run);
        response.setCaseResults(results);
        return response;
    }

    public EvaluationRunResponse getRun(Long runId) {
        AiEvaluationRun run = runMapper.selectById(runId);
        if (run == null) throw new IllegalArgumentException("评测运行记录不存在");
        requireRunOwner(run);
        EvaluationRunResponse response = new EvaluationRunResponse();
        response.setRun(run);
        response.setCaseResults(caseResultMapper.selectList(new QueryWrapper<AiEvaluationCaseResult>()
                .eq("run_id", runId).orderByAsc("id")));
        return response;
    }

    public EvaluationRunResponse abortRun(Long runId) {
        AiEvaluationRun run = runMapper.selectById(runId);
        if (run == null) throw new IllegalArgumentException("评测运行记录不存在");
        requireRunOwner(run);
        if (!"RUNNING".equals(run.getStatus())) {
            throw new IllegalArgumentException("只有 RUNNING 状态的评测可以终止");
        }
        run.setStatus("ABORTED");
        run.setErrorSummary("运行被创建者主动终止");
        runMapper.updateById(run);
        EvaluationRunResponse response = new EvaluationRunResponse();
        response.setRun(run);
        response.setCaseResults(caseResultMapper.selectList(new QueryWrapper<AiEvaluationCaseResult>()
                .eq("run_id", runId).orderByAsc("id")));
        return response;
    }

    public EvaluationRunComparisonResponse compareRuns(Long runId, Long baselineRunId) {
        AiEvaluationRun current = runMapper.selectById(runId);
        AiEvaluationRun baseline = runMapper.selectById(baselineRunId);
        if (current == null || baseline == null) throw new IllegalArgumentException("评测运行记录不存在");
        requireRunOwner(current);
        requireRunOwner(baseline);
        requireComparable(current, baseline);

        EvaluationRunComparisonResponse response = new EvaluationRunComparisonResponse();
        response.setBaselineRun(baseline);
        response.setCurrentRun(current);
        response.setRetrievalStrategyChanged(!Objects.equals(current.getRetrievalStrategyVersion(), baseline.getRetrievalStrategyVersion()));
        response.setMetricDeltas(metricDeltas(current, baseline));
        return response;
    }

    private void requireRunOwner(AiEvaluationRun run) {
        if (UserHolder.getUser() == null || run.getUserId() == null
                || !run.getUserId().equals(UserHolder.getUser().getId())) {
            throw new IllegalStateException("无权访问该评测运行记录");
        }
    }

    private void requireComparable(AiEvaluationRun current, AiEvaluationRun baseline) {
        if (!Objects.equals(current.getModel(), baseline.getModel())
                || !Objects.equals(current.getEvaluationDatasetVersion(), baseline.getEvaluationDatasetVersion())
                || !Objects.equals(current.getCaseCount(), baseline.getCaseCount())
                || !Objects.equals(current.getRankingEvaluatedCount(), baseline.getRankingEvaluatedCount())) {
            throw new IllegalArgumentException("模型、评测集或样本数量不一致，不能直接比较");
        }
    }

    private Map<String, Double> metricDeltas(AiEvaluationRun current, AiEvaluationRun baseline) {
        Map<String, Double> deltas = new LinkedHashMap<>();
        deltas.put("statusMatchRate", round(rate(current.getStatusMatchedCount(), current.getCaseCount())
                - rate(baseline.getStatusMatchedCount(), baseline.getCaseCount())));
        deltas.put("constraintMatchRate", round(rate(current.getConstraintMatchedCount(), current.getCaseCount())
                - rate(baseline.getConstraintMatchedCount(), baseline.getCaseCount())));
        deltas.put("completionRate", round(rate(current.getCompletedCount(), current.getCaseCount())
                - rate(baseline.getCompletedCount(), baseline.getCaseCount())));
        deltas.put("modelSuccessRate", round(rate(current.getModelSuccessCount(), current.getModelCallCount())
                - rate(baseline.getModelSuccessCount(), baseline.getModelCallCount())));
        deltas.put("modelFailureRate", round(rate(current.getModelFailureCount(), current.getModelCallCount())
                - rate(baseline.getModelFailureCount(), baseline.getModelCallCount())));
        deltas.put("promptTokenCount", round(value(current.getPromptTokenCount()) - value(baseline.getPromptTokenCount())));
        deltas.put("completionTokenCount", round(value(current.getCompletionTokenCount()) - value(baseline.getCompletionTokenCount())));
        deltas.put("avgTotalDurationMs", round(value(current.getAvgTotalDurationMs())
                - value(baseline.getAvgTotalDurationMs())));
        deltas.put("p95TotalDurationMs", round(value(current.getP95TotalDurationMs())
                - value(baseline.getP95TotalDurationMs())));
        deltas.put("avgExtractingDurationMs", round(value(current.getAvgExtractingDurationMs())
                - value(baseline.getAvgExtractingDurationMs())));
        deltas.put("hardConstraintViolationCount", round(value(current.getHardConstraintViolationCount())
                - value(baseline.getHardConstraintViolationCount())));
        deltas.put("factualConsistencyRate", round(rate(current.getFactualConsistentCount(), current.getCaseCount())
                - rate(baseline.getFactualConsistentCount(), baseline.getCaseCount())));
        deltas.put("recallAtK", round(value(current.getRecallAtK()) - value(baseline.getRecallAtK())));
        deltas.put("mrr", round(value(current.getMrr()) - value(baseline.getMrr())));
        deltas.put("evidenceCoverageRate", round(value(current.getEvidenceCoverageRate()) - value(baseline.getEvidenceCoverageRate())));
        return deltas;
    }

    private double rate(Integer numerator, Integer denominator) {
        if (denominator == null || denominator <= 0) return 0D;
        return value(numerator) / denominator;
    }

    private double value(Integer value) {
        return value == null ? 0D : value;
    }

    private double value(Double value) {
        return value == null ? 0D : value;
    }

    private double value(Long value) {
        return value == null ? 0D : value;
    }

    private int intValue(Integer value) {
        return value == null ? 0 : value;
    }

    private long longValue(Long value) {
        return value == null ? 0L : value;
    }

    private AiEvaluationCaseResult evaluate(Long runId, AiEvaluationCase evaluationCase) {
        AiEvaluationCaseResult result = new AiEvaluationCaseResult();
        result.setRunId(runId);
        result.setCaseId(evaluationCase.getId());
        try {
            DecisionResponse initialResponse = decisionService.decide(toRequest(evaluationCase));
            result.setSessionId(initialResponse.getSessionId());
            result.setInitialStatus(initialResponse.getStatus());
            result.setStatusMatched(evaluationCase.getExpectedStatus().equals(initialResponse.getStatus()));
            DecisionResponse response = initialResponse;
            if (hasFollowUp(evaluationCase)) {
                response = decisionService.continueDecision(initialResponse.getSessionId(), toFollowUp(evaluationCase));
                result.setFinalStatus(response.getStatus());
                result.setFinalStatusMatched(evaluationCase.getExpectedFinalStatus().equals(response.getStatus()));
            }
            result.setActualStatus(response.getStatus());
            String constraintMismatch = constraintMismatch(evaluationCase.getExpectedConstraintsJson(), initialResponse.getConstraints());
            result.setConstraintMatched(constraintMismatch == null);
            result.setConstraintMismatch(constraintMismatch);
            List<Long> expectedIds = expectedIds(evaluationCase.getExpectedShopIds());
            List<Long> actualIds = response.getRecommendations().stream()
                    .map(DecisionRecommendation::getShopId).collect(Collectors.toList());
            result.setRecommendedShopIds(actualIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
            if (!expectedIds.isEmpty()) {
                result.setRecallAtK(round(recall(expectedIds, actualIds)));
                result.setReciprocalRank(round(reciprocalRank(expectedIds, actualIds)));
            }
            result.setHardConstraintViolated(hasVerifiableHardConstraintViolation(response));
            result.setFactualConsistent(response.getMetrics().getFactualConsistent());
            if (!actualIds.isEmpty()) result.setEvidenceCoverageRate(response.getMetrics().getEvidenceCoverageRate());
            DecisionMetrics metrics = mergeMetrics(initialResponse.getMetrics(), response == initialResponse ? null : response.getMetrics());
            result.setModelCallCount(metrics.getModelCallCount());
            result.setModelSuccessCount(metrics.getModelSuccessCount());
            result.setModelFailureCount(metrics.getModelFailureCount());
            result.setPromptTokenCount(metrics.getPromptTokenCount());
            result.setCompletionTokenCount(metrics.getCompletionTokenCount());
            result.setTotalDurationMs(metrics.getTotalDurationMs());
            result.setExtractingDurationMs(metrics.getExtractingDurationMs());
        } catch (Exception e) {
            result.setActualStatus("FAILED");
            result.setStatusMatched(false);
            result.setConstraintMatched(false);
            result.setConstraintMismatch("DECISION_FAILED");
            result.setHardConstraintViolated(false);
            result.setFactualConsistent(false);
            result.setModelCallCount(0);
            result.setModelSuccessCount(0);
            result.setModelFailureCount(0);
            result.setPromptTokenCount(0);
            result.setCompletionTokenCount(0);
            result.setErrorMessage(trim(e.getMessage()));
        }
        return result;
    }

    private DecisionRequest toRequest(AiEvaluationCase evaluationCase) {
        DecisionRequest request = new DecisionRequest();
        request.setQuery(evaluationCase.getQueryText());
        request.setLatitude(evaluationCase.getLatitude());
        request.setLongitude(evaluationCase.getLongitude());
        request.setMaxCandidates(evaluationCase.getMaxCandidates());
        return request;
    }

    private boolean hasFollowUp(AiEvaluationCase evaluationCase) {
        return evaluationCase.getExpectedFinalStatus() != null && !evaluationCase.getExpectedFinalStatus().trim().isEmpty();
    }

    private DecisionFollowUpRequest toFollowUp(AiEvaluationCase evaluationCase) {
        DecisionFollowUpRequest request = new DecisionFollowUpRequest();
        request.setSelectedOptionId(evaluationCase.getFollowUpOptionId());
        request.setLatitude(evaluationCase.getFollowUpLatitude());
        request.setLongitude(evaluationCase.getFollowUpLongitude());
        return request;
    }

    private DecisionMetrics mergeMetrics(DecisionMetrics initial, DecisionMetrics followUp) {
        if (followUp == null) return initial;
        DecisionMetrics merged = new DecisionMetrics();
        merged.setModelCallCount(intValue(initial.getModelCallCount()) + intValue(followUp.getModelCallCount()));
        merged.setModelSuccessCount(intValue(initial.getModelSuccessCount()) + intValue(followUp.getModelSuccessCount()));
        merged.setModelFailureCount(intValue(initial.getModelFailureCount()) + intValue(followUp.getModelFailureCount()));
        merged.setPromptTokenCount(intValue(initial.getPromptTokenCount()) + intValue(followUp.getPromptTokenCount()));
        merged.setCompletionTokenCount(intValue(initial.getCompletionTokenCount()) + intValue(followUp.getCompletionTokenCount()));
        merged.setTotalDurationMs(longValue(initial.getTotalDurationMs()) + longValue(followUp.getTotalDurationMs()));
        merged.setExtractingDurationMs(longValue(initial.getExtractingDurationMs()) + longValue(followUp.getExtractingDurationMs()));
        return merged;
    }

    private List<Long> expectedIds(String expectedShopIds) {
        if (expectedShopIds == null || expectedShopIds.trim().isEmpty()) return Collections.emptyList();
        return Arrays.stream(expectedShopIds.split(",")).map(String::trim)
                .filter(value -> !value.isEmpty()).map(Long::valueOf).collect(Collectors.toList());
    }

    private double recall(List<Long> expectedIds, List<Long> actualIds) {
        if (expectedIds.isEmpty()) return actualIds.isEmpty() ? 1D : 0D;
        Set<Long> actual = new HashSet<>(actualIds);
        int matched = 0;
        for (Long expected : expectedIds) if (actual.contains(expected)) matched++;
        return (double) matched / expectedIds.size();
    }

    private double reciprocalRank(List<Long> expectedIds, List<Long> actualIds) {
        Set<Long> expected = new HashSet<>(expectedIds);
        for (int index = 0; index < actualIds.size(); index++) {
            if (expected.contains(actualIds.get(index))) return 1D / (index + 1);
        }
        return 0D;
    }

    private boolean hasVerifiableHardConstraintViolation(DecisionResponse response) {
        for (DecisionRecommendation item : response.getRecommendations()) {
            if (response.getConstraints().getBudgetPerPerson() > 0 && item.getAvgPrice() != null
                    && item.getAvgPrice() > response.getConstraints().getBudgetPerPerson()) return true;
            if (response.getConstraints().getRadiusKm() > 0 && item.getDistanceKm() != null
                    && item.getDistanceKm() > response.getConstraints().getRadiusKm()) return true;
        }
        return false;
    }

    private String constraintMismatch(String expectedJson, DecisionConstraints actual) throws Exception {
        if (expectedJson == null || expectedJson.trim().isEmpty()) return null;
        DecisionConstraints expected = objectMapper.readValue(expectedJson, DecisionConstraints.class);
        List<String> mismatches = new ArrayList<>();
        if (!expected.getCuisine().isEmpty() && !expected.getCuisine().equals(actual.getCuisine())) mismatches.add("cuisine");
        if (expected.getBudgetPerPerson() > 0 && !expected.getBudgetPerPerson().equals(actual.getBudgetPerPerson())) mismatches.add("budgetPerPerson");
        if (expected.getRadiusKm() > 0 && Math.abs(expected.getRadiusKm() - actual.getRadiusKm()) > 0.01D) mismatches.add("radiusKm");
        if (Boolean.TRUE.equals(expected.getNearby()) && !Boolean.TRUE.equals(actual.getNearby())) mismatches.add("nearby");
        if (!expected.getArrivalTime().isEmpty() && !expected.getArrivalTime().equals(actual.getArrivalTime())) mismatches.add("arrivalTime");
        if (!expected.getPreferences().isEmpty() && !sameStringSet(expected.getPreferences(), actual.getPreferences())) mismatches.add("preferences");
        return mismatches.isEmpty() ? null : String.join(",", mismatches);
    }

    private boolean sameStringSet(List<String> a, List<String> b) {
        List<String> left = a == null ? new ArrayList<String>() : a;
        List<String> right = b == null ? new ArrayList<String>() : b;
        return left.size() == right.size() && left.containsAll(right);
    }

    private void finishRun(AiEvaluationRun run, List<AiEvaluationCaseResult> results) {
        List<AiEvaluationCaseResult> failedResults = results.stream()
                .filter(item -> "FAILED".equals(item.getActualStatus())).collect(Collectors.toList());
        run.setStatus(failedResults.isEmpty() ? "COMPLETED" : "COMPLETED_WITH_ERRORS");
        run.setErrorSummary(failureSummary(failedResults));
        List<AiEvaluationCaseResult> rankingResults = results.stream()
                .filter(item -> item.getRecallAtK() != null).collect(Collectors.toList());
        List<AiEvaluationCaseResult> evidenceResults = results.stream()
                .filter(item -> item.getEvidenceCoverageRate() != null).collect(Collectors.toList());
        run.setRankingEvaluatedCount(rankingResults.size());
        List<AiEvaluationCaseResult> followUpResults = results.stream()
                .filter(item -> item.getFinalStatus() != null).collect(Collectors.toList());
        run.setFollowUpEvaluatedCount(followUpResults.size());
        run.setFollowUpStatusMatchedCount((int) followUpResults.stream()
                .filter(item -> Boolean.TRUE.equals(item.getFinalStatusMatched())).count());
        run.setCompletedCount((int) results.stream().filter(item -> "COMPLETED".equals(item.getActualStatus())).count());
        run.setStatusMatchedCount((int) results.stream().filter(item -> Boolean.TRUE.equals(item.getStatusMatched())).count());
        run.setConstraintMatchedCount((int) results.stream().filter(item -> Boolean.TRUE.equals(item.getConstraintMatched())).count());
        run.setHardConstraintViolationCount((int) results.stream().filter(item -> Boolean.TRUE.equals(item.getHardConstraintViolated())).count());
        run.setFactualConsistentCount((int) results.stream().filter(item -> Boolean.TRUE.equals(item.getFactualConsistent())).count());
        run.setRecallAtK(round(average(rankingResults.stream().map(AiEvaluationCaseResult::getRecallAtK).collect(Collectors.toList()))));
        run.setMrr(round(average(rankingResults.stream().map(AiEvaluationCaseResult::getReciprocalRank).collect(Collectors.toList()))));
        run.setEvidenceCoverageRate(round(average(evidenceResults.stream().map(AiEvaluationCaseResult::getEvidenceCoverageRate).collect(Collectors.toList()))));
        run.setModelCallCount(sum(results.stream().map(AiEvaluationCaseResult::getModelCallCount).collect(Collectors.toList())));
        run.setModelSuccessCount(sum(results.stream().map(AiEvaluationCaseResult::getModelSuccessCount).collect(Collectors.toList())));
        run.setModelFailureCount(sum(results.stream().map(AiEvaluationCaseResult::getModelFailureCount).collect(Collectors.toList())));
        run.setPromptTokenCount(sum(results.stream().map(AiEvaluationCaseResult::getPromptTokenCount).collect(Collectors.toList())));
        run.setCompletionTokenCount(sum(results.stream().map(AiEvaluationCaseResult::getCompletionTokenCount).collect(Collectors.toList())));
        List<Long> totalDurations = results.stream().map(AiEvaluationCaseResult::getTotalDurationMs)
                .filter(value -> value != null).collect(Collectors.toList());
        List<Long> extractingDurations = results.stream().map(AiEvaluationCaseResult::getExtractingDurationMs)
                .filter(value -> value != null).collect(Collectors.toList());
        run.setAvgTotalDurationMs(averageDuration(totalDurations));
        run.setP95TotalDurationMs(p95Duration(totalDurations));
        run.setAvgExtractingDurationMs(averageDuration(extractingDurations));
    }

    private long averageDuration(List<Long> values) {
        if (values.isEmpty()) return 0L;
        long total = 0L;
        for (Long value : values) total += value;
        return Math.round((double) total / values.size());
    }

    private long p95Duration(List<Long> values) {
        if (values.isEmpty()) return 0L;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        return sorted.get((int) Math.ceil(sorted.size() * 0.95D) - 1);
    }

    private String failureSummary(List<AiEvaluationCaseResult> failedResults) {
        if (failedResults.isEmpty()) return null;
        String caseIds = failedResults.stream().limit(10).map(AiEvaluationCaseResult::getCaseId)
                .map(String::valueOf).collect(Collectors.joining(","));
        String suffix = failedResults.size() > 10 ? "等" : "";
        return "失败用例数=" + failedResults.size() + "，caseId=" + caseIds + suffix;
    }

    private double average(List<Double> values) {
        if (values.isEmpty()) return 0D;
        double total = 0D;
        for (Double value : values) total += value == null ? 0D : value;
        return total / values.size();
    }

    private int sum(List<Integer> values) {
        int total = 0;
        for (Integer value : values) total += value == null ? 0 : value;
        return total;
    }

    private String trim(String value) {
        if (value == null) return "未知错误";
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private double round(double value) {
        return Math.round(value * 10000D) / 10000D;
    }
}
