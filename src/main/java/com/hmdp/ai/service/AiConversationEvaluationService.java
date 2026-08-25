package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.ChatLocationInput;
import com.hmdp.ai.dto.ChatMessageRequest;
import com.hmdp.ai.dto.ChatMessageResponse;
import com.hmdp.ai.dto.ContextRewriteResult;
import com.hmdp.ai.dto.ConversationEvaluationRunResponse;
import com.hmdp.ai.dto.ConversationEvaluationRunComparisonResponse;
import com.hmdp.ai.dto.ConversationEvaluationDiagnosticsResponse;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.entity.AiConversationEvaluationCase;
import com.hmdp.ai.entity.AiConversationEvaluationCaseResult;
import com.hmdp.ai.entity.AiConversationEvaluationRun;
import com.hmdp.ai.entity.AiAgentToolCall;
import com.hmdp.ai.mapper.AiConversationEvaluationCaseMapper;
import com.hmdp.ai.mapper.AiConversationEvaluationCaseResultMapper;
import com.hmdp.ai.mapper.AiConversationEvaluationRunMapper;
import com.hmdp.ai.mapper.AiAgentToolCallMapper;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.HashSet;
import java.util.stream.Collectors;

/** Executes scripted user turns through the same chat entry point used by the web console. */
@Service
public class AiConversationEvaluationService {
    @Resource private ChatOrchestrationService chatOrchestrationService;
    @Resource private AiConversationEvaluationCaseMapper caseMapper;
    @Resource private AiConversationEvaluationRunMapper runMapper;
    @Resource private AiConversationEvaluationCaseResultMapper resultMapper;
    @Resource private AiAgentToolCallMapper toolCallMapper;
    @Resource private ShopMapper shopMapper;
    @Resource private ObjectMapper objectMapper;
    @Resource private AiProperties aiProperties;
    private Executor evaluationExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ConversationEvaluationRunResponse runActiveCases() {
        return runCases(aiProperties.getConversationEvaluationDatasetVersion());
    }

    public ConversationEvaluationRunResponse runHoldoutCases() {
        return runCases(aiProperties.getConversationHoldoutDatasetVersion());
    }

    /**
     * Creates a run synchronously, then evaluates it outside the HTTP request.
     * Callers can poll {@link #getRun(Long)} without holding an upstream proxy
     * connection for the full multi-turn trajectory duration.
     */
    public ConversationEvaluationRunResponse submitActiveCases() {
        return submitCases(aiProperties.getConversationEvaluationDatasetVersion());
    }

    public ConversationEvaluationRunResponse submitHoldoutCases() {
        return submitCases(aiProperties.getConversationHoldoutDatasetVersion());
    }

    private ConversationEvaluationRunResponse runCases(String datasetVersion) {
        List<AiConversationEvaluationCase> cases = activeCases(datasetVersion);
        AiConversationEvaluationRun run = createRun(datasetVersion, cases);

        List<AiConversationEvaluationCaseResult> results = executeRun(run, cases);
        return response(run, results);
    }

    private ConversationEvaluationRunResponse submitCases(String datasetVersion) {
        List<AiConversationEvaluationCase> cases = activeCases(datasetVersion);
        AiConversationEvaluationRun run = createRun(datasetVersion, cases);
        UserDTOSnapshot submitter = UserDTOSnapshot.capture(UserHolder.getUser());
        evaluationExecutor.execute(() -> executeAsync(run, cases, submitter));
        return response(run, Collections.emptyList());
    }

    private void executeAsync(AiConversationEvaluationRun run, List<AiConversationEvaluationCase> cases,
                              UserDTOSnapshot submitter) {
        try {
            submitter.restore();
            executeRun(run, cases);
        } catch (Exception e) {
            run.setStatus("FAILED");
            run.setErrorSummary(compact(e.getMessage()));
            runMapper.updateById(run);
        } finally {
            UserHolder.removeUser();
        }
    }

    private List<AiConversationEvaluationCaseResult> executeRun(AiConversationEvaluationRun run,
                                                                  List<AiConversationEvaluationCase> cases) {
        List<AiConversationEvaluationCaseResult> results = new ArrayList<>();
        for (AiConversationEvaluationCase evaluationCase : cases) {
            AiConversationEvaluationCaseResult result = evaluate(run.getId(), evaluationCase);
            resultMapper.insert(result);
            results.add(result);
        }
        finish(run, results);
        runMapper.updateById(run);
        return results;
    }

    private List<AiConversationEvaluationCase> activeCases(String datasetVersion) {
        List<AiConversationEvaluationCase> cases = caseMapper.selectList(new QueryWrapper<AiConversationEvaluationCase>()
                .eq("active", true).eq("dataset_version", datasetVersion).orderByAsc("id"));
        if (cases.isEmpty()) throw new IllegalStateException("没有启用的对话轨迹评测用例");
        return cases;
    }

    private AiConversationEvaluationRun createRun(String datasetVersion, List<AiConversationEvaluationCase> cases) {
        AiConversationEvaluationRun run = new AiConversationEvaluationRun();
        run.setUserId(UserHolder.getUser() == null ? null : UserHolder.getUser().getId());
        run.setModel(aiProperties.getModel());
        run.setDatasetVersion(datasetVersion);
        run.setCaseCount(cases.size());
        run.setStatus("RUNNING");
        runMapper.insert(run);
        return run;
    }

    private ConversationEvaluationRunResponse response(AiConversationEvaluationRun run,
                                                        List<AiConversationEvaluationCaseResult> results) {
        ConversationEvaluationRunResponse response = new ConversationEvaluationRunResponse();
        response.setRun(run);
        response.setCaseResults(results);
        return response;
    }

    private static final class UserDTOSnapshot {
        private final Long id;
        private final String nickName;
        private final String icon;

        private UserDTOSnapshot(Long id, String nickName, String icon) {
            this.id = id;
            this.nickName = nickName;
            this.icon = icon;
        }

        static UserDTOSnapshot capture(com.hmdp.dto.UserDTO user) {
            return user == null ? new UserDTOSnapshot(null, null, null)
                    : new UserDTOSnapshot(user.getId(), user.getNickName(), user.getIcon());
        }

        void restore() {
            if (id == null) return;
            com.hmdp.dto.UserDTO user = new com.hmdp.dto.UserDTO();
            user.setId(id);
            user.setNickName(nickName);
            user.setIcon(icon);
            UserHolder.saveUser(user);
        }
    }

    public ConversationEvaluationRunResponse getRun(Long runId) {
        AiConversationEvaluationRun run = runMapper.selectById(runId);
        if (run == null) throw new IllegalArgumentException("对话评测运行记录不存在");
        requireOwner(run);
        ConversationEvaluationRunResponse response = new ConversationEvaluationRunResponse();
        response.setRun(run);
        response.setCaseResults(resultMapper.selectList(new QueryWrapper<AiConversationEvaluationCaseResult>()
                .eq("run_id", runId).orderByAsc("id")));
        return response;
    }

    public ConversationEvaluationRunComparisonResponse compareRuns(Long runId, Long baselineRunId) {
        AiConversationEvaluationRun current = runMapper.selectById(runId);
        AiConversationEvaluationRun baseline = runMapper.selectById(baselineRunId);
        if (current == null || baseline == null) throw new IllegalArgumentException("对话评测运行记录不存在");
        requireOwner(current);
        requireOwner(baseline);
        if (!current.getDatasetVersion().equals(baseline.getDatasetVersion())
                || !current.getCaseCount().equals(baseline.getCaseCount())) {
            throw new IllegalArgumentException("评测数据集或用例数量不一致，不能直接比较");
        }
        Map<String, Double> deltas = new LinkedHashMap<>();
        deltas.put("routeMatchRate", round(rate(current.getRouteMatchedCount(), current.getCaseCount())
                - rate(baseline.getRouteMatchedCount(), baseline.getCaseCount())));
        deltas.put("contextRewriteMatchRate", round(rate(current.getContextRewriteMatchedCount(), current.getContextRewriteExpectedCount())
                - rate(baseline.getContextRewriteMatchedCount(), baseline.getContextRewriteExpectedCount())));
        deltas.put("toolMatchRate", round(rate(current.getToolMatchedCount(), current.getCaseCount())
                - rate(baseline.getToolMatchedCount(), baseline.getCaseCount())));
        deltas.put("toolCoverageRate", round(rate(current.getToolCoveredCount(), current.getToolExpectedCount())
                - rate(baseline.getToolCoveredCount(), baseline.getToolExpectedCount())));
        deltas.put("localityMatchRate", round(rate(current.getLocalityMatchedCount(), current.getCaseCount())
                - rate(baseline.getLocalityMatchedCount(), baseline.getCaseCount())));
        deltas.put("finalStatusMatchRate", round(rate(current.getFinalStatusMatchedCount(), current.getCaseCount())
                - rate(baseline.getFinalStatusMatchedCount(), baseline.getCaseCount())));
        deltas.put("completionRate", round(rate(current.getCompletedCount(), current.getCaseCount())
                - rate(baseline.getCompletedCount(), baseline.getCaseCount())));
        deltas.put("avgDurationMs", round(value(current.getAvgDurationMs()) - value(baseline.getAvgDurationMs())));

        ConversationEvaluationRunComparisonResponse response = new ConversationEvaluationRunComparisonResponse();
        response.setBaselineRun(baseline);
        response.setCurrentRun(current);
        response.setMetricDeltas(deltas);
        return response;
    }

    public ConversationEvaluationDiagnosticsResponse getDiagnostics(Long runId) {
        AiConversationEvaluationRun run = runMapper.selectById(runId);
        if (run == null) throw new IllegalArgumentException("对话评测运行记录不存在");
        requireOwner(run);
        List<AiConversationEvaluationCaseResult> results = resultMapper.selectList(new QueryWrapper<AiConversationEvaluationCaseResult>()
                .eq("run_id", runId).orderByAsc("id"));
        Map<Long, AiConversationEvaluationCase> casesById = caseMapper.selectBatchIds(results.stream()
                        .map(AiConversationEvaluationCaseResult::getCaseId).distinct().collect(Collectors.toList()))
                .stream().collect(Collectors.toMap(AiConversationEvaluationCase::getId, item -> item));
        List<ConversationEvaluationDiagnosticsResponse.CaseDiagnostic> failures = results.stream()
                .filter(this::hasFailure).map(result -> toDiagnostic(result, casesById.get(result.getCaseId())))
                .collect(Collectors.toList());
        Map<String, Integer> failureCounts = new LinkedHashMap<>();
        failureCounts.put("route", (int) results.stream().filter(item -> !Boolean.TRUE.equals(item.getRouteMatched())).count());
        failureCounts.put("contextRewrite", (int) results.stream().filter(item -> !Boolean.TRUE.equals(item.getContextRewriteMatched())).count());
        failureCounts.put("toolCoverage", (int) results.stream().filter(item -> !Boolean.TRUE.equals(item.getToolMatched())).count());
        failureCounts.put("toolArguments", (int) results.stream().filter(item -> Boolean.FALSE.equals(item.getToolArgumentsMatched())).count());
        failureCounts.put("locality", (int) results.stream().filter(item -> !Boolean.TRUE.equals(item.getLocalityMatched())).count());
        failureCounts.put("finalStatus", (int) results.stream().filter(item -> !Boolean.TRUE.equals(item.getFinalStatusMatched())).count());
        failureCounts.put("shop", (int) results.stream().filter(item -> !Boolean.TRUE.equals(item.getShopMatched())).count());
        failureCounts.put("execution", (int) results.stream().filter(item -> item.getErrorMessage() != null && !item.getErrorMessage().isEmpty()).count());
        ConversationEvaluationDiagnosticsResponse response = new ConversationEvaluationDiagnosticsResponse();
        response.setRun(run);
        response.setFailureCounts(failureCounts);
        response.setFailures(failures);
        return response;
    }

    private AiConversationEvaluationCaseResult evaluate(Long runId, AiConversationEvaluationCase evaluationCase) {
        AiConversationEvaluationCaseResult result = new AiConversationEvaluationCaseResult();
        result.setRunId(runId);
        result.setCaseId(evaluationCase.getId());
        String casePart = evaluationCase.getCaseCode().toLowerCase().replace('_', '-');
        if (casePart.length() > 48) casePart = casePart.substring(0, 48);
        String chatId = "eval-" + casePart + "-" + UUID.randomUUID().toString().substring(0, 8);
        result.setChatId(chatId);
        long startedAt = System.currentTimeMillis();
        try {
            List<Map<String, Object>> turns = objectMapper.readValue(evaluationCase.getTurnsJson(), new TypeReference<List<Map<String, Object>>>() { });
            List<String> routes = new ArrayList<>();
            List<Long> finalShopIds = new ArrayList<>();
            Set<Long> decisionSessionIds = new HashSet<>();
            List<Map<String, Object>> outputs = new ArrayList<>();
            List<ContextRewriteResult> contextRewrites = new ArrayList<>();
            List<List<DecisionRecommendation>> recommendationSnapshots = new ArrayList<>();
            String finalStatus = null;
            for (Map<String, Object> turn : turns) {
                ChatMessageRequest request = new ChatMessageRequest();
                request.setChatId(chatId);
                request.setMessage(String.valueOf(turn.get("message")));
                if (turn.get("selectedOptionId") != null) request.setSelectedOptionId(String.valueOf(turn.get("selectedOptionId")));
                applyLocation(turn.get("location"), request);
                ChatMessageResponse response = chatOrchestrationService.chat(request);
                routes.add(response.getRoute());
                contextRewrites.add(response.getContextRewrite());
                if (response.getDecisionSessionId() != null) decisionSessionIds.add(response.getDecisionSessionId());
                if (response.getDecisionStatus() != null) finalStatus = response.getDecisionStatus();
                if ("START_DECISION".equals(response.getRoute())) finalShopIds.clear();
                if (response.getDecision() != null) {
                    finalStatus = response.getDecision().getStatus();
                    if (!response.getDecision().getRecommendations().isEmpty()) {
                        finalShopIds.clear();
                        for (DecisionRecommendation item : response.getDecision().getRecommendations()) finalShopIds.add(item.getShopId());
                    }
                }
                recommendationSnapshots.add(response.getDecision() == null
                        ? Collections.emptyList() : new ArrayList<>(response.getDecision().getRecommendations()));
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("route", response.getRoute());
                output.put("decisionStatus", response.getDecisionStatus());
                output.put("answer", compact(response.getAnswer()));
                output.put("contextRewrite", compactContextRewrite(response.getContextRewrite()));
                outputs.add(output);
            }
            List<String> expectedRoutes = objectMapper.readValue(evaluationCase.getExpectedRoutesJson(), new TypeReference<List<String>>() { });
            result.setActualRoutesJson(objectMapper.writeValueAsString(routes));
            result.setActualContextRewritesJson(objectMapper.writeValueAsString(compactContextRewrites(contextRewrites)));
            result.setActualFinalStatus(finalStatus);
            result.setRecommendedShopIds(finalShopIds.stream().distinct().map(String::valueOf).collect(Collectors.joining(",")));
            result.setRouteMatched(expectedRoutes.equals(routes));
            ContextRewriteCoverage rewriteCoverage = evaluateContextRewriteCoverage(
                    evaluationCase.getExpectedContextRewritesJson(), contextRewrites, recommendationSnapshots);
            result.setContextRewriteMatched(rewriteCoverage.matched);
            result.setExpectedContextRewriteCount(rewriteCoverage.expectedCount);
            result.setMatchedContextRewriteCount(rewriteCoverage.matchedCount);
            List<AiAgentToolCall> actualToolCalls = toolCalls(decisionSessionIds);
            List<String> actualTools = actualToolCalls.stream().map(AiAgentToolCall::getToolName).collect(Collectors.toList());
            result.setActualToolNamesJson(objectMapper.writeValueAsString(actualTools));
            result.setActualToolCallsJson(objectMapper.writeValueAsString(compactToolCalls(actualToolCalls)));
            ToolCoverage toolCoverage = evaluateToolCoverage(evaluationCase.getExpectedToolNamesJson(), actualTools);
            result.setExpectedToolCount(toolCoverage.expectedCount);
            result.setCoveredToolCount(toolCoverage.coveredCount);
            result.setUnexpectedToolCount(toolCoverage.unexpectedCount);
            result.setToolMatched(toolCoverage.matched);
            result.setToolArgumentsMatched(toolArgumentsMatched(evaluationCase.getExpectedToolArgumentsJson(), actualToolCalls));
            result.setLocalityMatched(matchesExpectedCity(evaluationCase.getExpectedCity(), finalShopIds));
            result.setFinalStatusMatched(equalsExpected(evaluationCase.getExpectedFinalStatus(), finalStatus));
            result.setShopMatched(expectedShopsMatched(evaluationCase.getExpectedShopIds(), finalShopIds));
            result.setTurnOutputsJson(objectMapper.writeValueAsString(outputs));
        } catch (Exception e) {
            result.setRouteMatched(false);
            result.setContextRewriteMatched(false);
            result.setToolMatched(false);
            result.setLocalityMatched(false);
            result.setFinalStatusMatched(false);
            result.setShopMatched(false);
            result.setErrorMessage(compact(e.getMessage()));
        }
        result.setDurationMs(System.currentTimeMillis() - startedAt);
        return result;
    }

    private void applyLocation(Object rawLocation, ChatMessageRequest request) {
        if (!(rawLocation instanceof Map)) return;
        Map<?, ?> location = (Map<?, ?>) rawLocation;
        ChatLocationInput input = new ChatLocationInput();
        input.setLatitude(number(location.get("latitude")));
        input.setLongitude(number(location.get("longitude")));
        input.setSource("EVALUATION");
        request.setLocation(input);
    }

    private Double number(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        return value == null ? null : Double.valueOf(String.valueOf(value));
    }

    private boolean equalsExpected(String expected, String actual) {
        if (expected == null || expected.trim().isEmpty()) return true;
        for (String allowed : expected.split("\\|")) {
            if (allowed.trim().equals(actual)) return true;
        }
        return false;
    }

    private boolean hasFailure(AiConversationEvaluationCaseResult result) {
        return !Boolean.TRUE.equals(result.getRouteMatched())
                || !Boolean.TRUE.equals(result.getContextRewriteMatched())
                || !Boolean.TRUE.equals(result.getToolMatched())
                || Boolean.FALSE.equals(result.getToolArgumentsMatched())
                || !Boolean.TRUE.equals(result.getLocalityMatched())
                || !Boolean.TRUE.equals(result.getFinalStatusMatched())
                || !Boolean.TRUE.equals(result.getShopMatched())
                || (result.getErrorMessage() != null && !result.getErrorMessage().isEmpty());
    }

    private ConversationEvaluationDiagnosticsResponse.CaseDiagnostic toDiagnostic(AiConversationEvaluationCaseResult result,
                                                                                    AiConversationEvaluationCase evaluationCase) {
        ConversationEvaluationDiagnosticsResponse.CaseDiagnostic diagnostic = new ConversationEvaluationDiagnosticsResponse.CaseDiagnostic();
        diagnostic.setCaseId(result.getCaseId());
        if (evaluationCase != null) {
            diagnostic.setCaseCode(evaluationCase.getCaseCode());
            diagnostic.setNotes(evaluationCase.getNotes());
            diagnostic.setExpectedRoutesJson(evaluationCase.getExpectedRoutesJson());
            diagnostic.setExpectedContextRewritesJson(evaluationCase.getExpectedContextRewritesJson());
            diagnostic.setExpectedToolNamesJson(evaluationCase.getExpectedToolNamesJson());
            diagnostic.setExpectedToolArgumentsJson(evaluationCase.getExpectedToolArgumentsJson());
            diagnostic.setExpectedFinalStatus(evaluationCase.getExpectedFinalStatus());
            diagnostic.setExpectedCity(evaluationCase.getExpectedCity());
        }
        diagnostic.setActualRoutesJson(result.getActualRoutesJson());
        diagnostic.setActualContextRewritesJson(result.getActualContextRewritesJson());
        diagnostic.setActualToolNamesJson(result.getActualToolNamesJson());
        diagnostic.setActualToolCallsJson(result.getActualToolCallsJson());
        diagnostic.setActualFinalStatus(result.getActualFinalStatus());
        diagnostic.setRecommendedShopIds(result.getRecommendedShopIds());
        diagnostic.setRouteMatched(result.getRouteMatched());
        diagnostic.setContextRewriteMatched(result.getContextRewriteMatched());
        diagnostic.setToolMatched(result.getToolMatched());
        diagnostic.setToolArgumentsMatched(result.getToolArgumentsMatched());
        diagnostic.setLocalityMatched(result.getLocalityMatched());
        diagnostic.setFinalStatusMatched(result.getFinalStatusMatched());
        diagnostic.setShopMatched(result.getShopMatched());
        diagnostic.setDurationMs(result.getDurationMs());
        diagnostic.setErrorMessage(result.getErrorMessage());
        return diagnostic;
    }

    private boolean expectedShopsMatched(String expected, List<Long> actual) {
        if (expected == null || expected.trim().isEmpty()) return true;
        List<String> expectedIds = new ArrayList<>();
        Collections.addAll(expectedIds, expected.split(","));
        return actual.stream().map(String::valueOf).anyMatch(expectedIds::contains);
    }

    private List<AiAgentToolCall> toolCalls(Set<Long> sessionIds) {
        if (sessionIds.isEmpty()) return Collections.emptyList();
        return toolCallMapper.selectList(new QueryWrapper<AiAgentToolCall>()
                .in("session_id", sessionIds).orderByAsc("id"));
    }

    private ToolCoverage evaluateToolCoverage(String expectedJson, List<String> actual) throws Exception {
        if (expectedJson == null || expectedJson.trim().isEmpty()) return new ToolCoverage(0, 0, actual.size(), actual.isEmpty());
        List<String> expected = objectMapper.readValue(expectedJson, new TypeReference<List<String>>() { });
        Set<String> required = new LinkedHashSet<>(expected);
        Set<String> actualSet = new LinkedHashSet<>(actual);
        int covered = (int) required.stream().filter(actualSet::contains).count();
        int unexpected = (int) actual.stream().filter(item -> !required.contains(item)).count();
        boolean matched = required.isEmpty() ? actual.isEmpty() : covered == required.size();
        return new ToolCoverage(required.size(), covered, unexpected, matched);
    }

    private List<Map<String, Object>> compactToolCalls(List<AiAgentToolCall> calls) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (AiAgentToolCall call : calls) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", call.getToolName());
            value.put("input", compact(call.getToolInputJson()));
            value.put("status", call.getStatus());
            values.add(value);
        }
        return values;
    }

    private ContextRewriteCoverage evaluateContextRewriteCoverage(String expectedJson,
                                                                   List<ContextRewriteResult> actual,
                                                                   List<List<DecisionRecommendation>> recommendationSnapshots) throws Exception {
        if (expectedJson == null || expectedJson.trim().isEmpty()) return new ContextRewriteCoverage(0, 0, true);
        List<Map<String, Object>> expected = objectMapper.readValue(expectedJson,
                new TypeReference<List<Map<String, Object>>>() { });
        int expectedCount = 0;
        int matchedCount = 0;
        for (int index = 0; index < expected.size(); index++) {
            Map<String, Object> expectation = expected.get(index);
            if (expectation == null || expectation.isEmpty()) continue;
            expectedCount++;
            ContextRewriteResult actualResult = index < actual.size() ? actual.get(index) : null;
            if (matchesRewriteExpectation(expectation, actualResult, recommendationSnapshots, index)) matchedCount++;
        }
        return new ContextRewriteCoverage(expectedCount, matchedCount, expectedCount == matchedCount);
    }

    private boolean matchesRewriteExpectation(Map<String, Object> expectation, ContextRewriteResult actual,
                                              List<List<DecisionRecommendation>> recommendationSnapshots, int turnIndex) {
        if (actual == null) return false;
        if (expectation.containsKey("applied")
                && !Boolean.valueOf(String.valueOf(expectation.get("applied"))).equals(actual.getApplied())) return false;
        String contains = stringValue(expectation.get("contains"));
        if (contains != null && (actual.getRewrittenQuery() == null || !actual.getRewrittenQuery().contains(contains))) return false;
        Integer candidateOrdinal = integerValue(expectation.get("candidateOrdinal"));
        if (candidateOrdinal != null) {
            String candidateName = candidateNameAtOrdinal(recommendationSnapshots, turnIndex, candidateOrdinal);
            if (candidateName == null || actual.getRewrittenQuery() == null || !actual.getRewrittenQuery().contains(candidateName)) return false;
        }
        String reason = stringValue(expectation.get("reason"));
        return reason == null || reason.equals(actual.getReason());
    }

    private String candidateNameAtOrdinal(List<List<DecisionRecommendation>> snapshots, int turnIndex, int ordinal) {
        if (ordinal < 1) return null;
        for (int index = Math.min(turnIndex - 1, snapshots.size() - 1); index >= 0; index--) {
            List<DecisionRecommendation> candidates = snapshots.get(index);
            if (candidates != null && candidates.size() >= ordinal) return candidates.get(ordinal - 1).getShopName();
        }
        return null;
    }

    private List<Map<String, Object>> compactContextRewrites(List<ContextRewriteResult> rewrites) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (ContextRewriteResult rewrite : rewrites) values.add(compactContextRewrite(rewrite));
        return values;
    }

    private Map<String, Object> compactContextRewrite(ContextRewriteResult rewrite) {
        Map<String, Object> value = new LinkedHashMap<>();
        if (rewrite == null) return value;
        value.put("originalQuery", compact(rewrite.getOriginalQuery()));
        value.put("rewrittenQuery", compact(rewrite.getRewrittenQuery()));
        value.put("applied", rewrite.getApplied());
        value.put("usedModel", rewrite.getUsedModel());
        value.put("reason", rewrite.getReason());
        return value;
    }

    private String stringValue(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Boolean toolArgumentsMatched(String expectedJson, List<AiAgentToolCall> calls) throws Exception {
        if (expectedJson == null || expectedJson.trim().isEmpty()) return null;
        Map<String, Map<String, Object>> expected = objectMapper.readValue(expectedJson,
                new TypeReference<Map<String, Map<String, Object>>>() { });
        for (Map.Entry<String, Map<String, Object>> entry : expected.entrySet()) {
            boolean matched = false;
            for (AiAgentToolCall call : calls) {
                if (!entry.getKey().equals(call.getToolName())) continue;
                Map<String, Object> actual = call.getToolInputJson() == null || call.getToolInputJson().trim().isEmpty()
                        ? Collections.emptyMap() : objectMapper.readValue(call.getToolInputJson(), new TypeReference<Map<String, Object>>() { });
                if (entry.getValue().entrySet().stream().allMatch(item -> String.valueOf(item.getValue()).equals(String.valueOf(actual.get(item.getKey()))))) {
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
        }
        return true;
    }

    private boolean matchesExpectedCity(String expectedCity, List<Long> shopIds) {
        if (expectedCity == null || expectedCity.trim().isEmpty() || shopIds.isEmpty()) return true;
        List<Shop> shops = shopMapper.selectBatchIds(shopIds.stream().distinct().collect(Collectors.toList()));
        return shops.size() == new HashSet<>(shopIds).size()
                && shops.stream().allMatch(shop -> expectedCity.equals(shop.getCity()));
    }

    private void finish(AiConversationEvaluationRun run, List<AiConversationEvaluationCaseResult> results) {
        long failed = results.stream().filter(item -> item.getErrorMessage() != null).count();
        run.setStatus(failed == 0 ? "COMPLETED" : "COMPLETED_WITH_ERRORS");
        run.setRouteMatchedCount((int) results.stream().filter(item -> Boolean.TRUE.equals(item.getRouteMatched())).count());
        run.setContextRewriteExpectedCount(results.stream().map(AiConversationEvaluationCaseResult::getExpectedContextRewriteCount)
                .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum());
        run.setContextRewriteMatchedCount(results.stream().map(AiConversationEvaluationCaseResult::getMatchedContextRewriteCount)
                .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum());
        run.setToolMatchedCount((int) results.stream().filter(item -> Boolean.TRUE.equals(item.getToolMatched())).count());
        run.setToolExpectedCount(results.stream().map(AiConversationEvaluationCaseResult::getExpectedToolCount)
                .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum());
        run.setToolCoveredCount(results.stream().map(AiConversationEvaluationCaseResult::getCoveredToolCount)
                .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum());
        run.setLocalityMatchedCount((int) results.stream().filter(item -> Boolean.TRUE.equals(item.getLocalityMatched())).count());
        run.setFinalStatusMatchedCount((int) results.stream().filter(item -> Boolean.TRUE.equals(item.getFinalStatusMatched())).count());
        run.setShopMatchedCount((int) results.stream().filter(item -> Boolean.TRUE.equals(item.getShopMatched())).count());
        run.setCompletedCount((int) (results.size() - failed));
        run.setAvgDurationMs(results.isEmpty() ? 0L : Math.round(results.stream().mapToLong(AiConversationEvaluationCaseResult::getDurationMs).average().orElse(0D)));
        run.setErrorSummary(failed == 0 ? null : "失败用例数=" + failed);
    }

    private void requireOwner(AiConversationEvaluationRun run) {
        if (UserHolder.getUser() == null || run.getUserId() == null || !run.getUserId().equals(UserHolder.getUser().getId())) {
            throw new IllegalStateException("无权访问该对话评测运行记录");
        }
    }

    private double rate(Integer numerator, Integer denominator) {
        if (denominator == null || denominator <= 0) return 0D;
        return (double) value(numerator) / denominator;
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }

    private double round(double value) {
        return Math.round(value * 10000D) / 10000D;
    }

    private String compact(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() > 300 ? normalized.substring(0, 300) + "..." : normalized;
    }

    private static final class ToolCoverage {
        private final int expectedCount;
        private final int coveredCount;
        private final int unexpectedCount;
        private final boolean matched;

        private ToolCoverage(int expectedCount, int coveredCount, int unexpectedCount, boolean matched) {
            this.expectedCount = expectedCount;
            this.coveredCount = coveredCount;
            this.unexpectedCount = unexpectedCount;
            this.matched = matched;
        }
    }

    private static final class ContextRewriteCoverage {
        private final int expectedCount;
        private final int matchedCount;
        private final boolean matched;

        private ContextRewriteCoverage(int expectedCount, int matchedCount, boolean matched) {
            this.expectedCount = expectedCount;
            this.matchedCount = matchedCount;
            this.matched = matched;
        }
    }
}
