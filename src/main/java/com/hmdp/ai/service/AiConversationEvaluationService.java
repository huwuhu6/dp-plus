package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.ChatLocationInput;
import com.hmdp.ai.dto.ChatMessageRequest;
import com.hmdp.ai.dto.ChatMessageResponse;
import com.hmdp.ai.dto.ChatStreamEventData;
import com.hmdp.ai.dto.ContextRewriteResult;
import com.hmdp.ai.dto.ConversationEvaluationRunResponse;
import com.hmdp.ai.dto.ConversationEvaluationRunComparisonResponse;
import com.hmdp.ai.dto.ConversationEvaluationDiagnosticsResponse;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.dto.ConversationWorkingMemory;
import com.hmdp.ai.entity.AiConversationEvaluationCase;
import com.hmdp.ai.entity.AiConversationEvaluationCaseResult;
import com.hmdp.ai.entity.AiConversationEvaluationRun;
import com.hmdp.ai.entity.AiChatSession;
import com.hmdp.ai.entity.AiAgentToolCall;
import com.hmdp.ai.entity.AiConversationEvent;
import com.hmdp.ai.entity.AiDecisionMetric;
import com.hmdp.ai.mapper.AiConversationEvaluationCaseMapper;
import com.hmdp.ai.mapper.AiConversationEvaluationCaseResultMapper;
import com.hmdp.ai.mapper.AiConversationEvaluationRunMapper;
import com.hmdp.ai.mapper.AiAgentToolCallMapper;
import com.hmdp.ai.mapper.AiConversationEventMapper;
import com.hmdp.ai.mapper.AiDecisionMetricMapper;
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
    @Resource private ConversationEvaluationDatasetLoader datasetLoader;
    @Resource private AiConversationEvaluationRunMapper runMapper;
    @Resource private AiConversationEvaluationCaseResultMapper resultMapper;
    @Resource private AiConversationEventMapper conversationEventMapper;
    @Resource private ConversationEventService conversationEventService;
    // Compatibility fallback for isolated tests and historical runs before V40.
    @Resource private AiAgentToolCallMapper toolCallMapper;
    @Resource private AiDecisionMetricMapper decisionMetricMapper;
    @Resource private ConversationStateService conversationStateService;
    @Resource private ShopMapper shopMapper;
    @Resource private ObjectMapper objectMapper;
    @Resource private AiProperties aiProperties;
    @Resource private AiModelCallObservationService modelCallObservationService;
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

    public ConversationEvaluationRunResponse submitRobustnessCases() {
        return submitCases(aiProperties.getConversationRobustnessDatasetVersion());
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
        // 优先从 Git 版本化 JSONL 加载，资源不存在时 Loader 内部降级到 MySQL
        List<AiConversationEvaluationCase> cases = datasetLoader.loadCases(datasetVersion);
        if (cases.isEmpty()) throw new IllegalStateException("没有启用的对话轨迹评测用例");
        return cases;
    }

    private AiConversationEvaluationRun createRun(String datasetVersion, List<AiConversationEvaluationCase> cases) {
        AiConversationEvaluationRun run = new AiConversationEvaluationRun();
        run.setUserId(UserHolder.getUser() == null ? null : UserHolder.getUser().getId());
        run.setModel(aiProperties.getModel());
        run.setDatasetVersion(datasetVersion);
        run.setGitCommit(resolveGitCommit());
        run.setCaseCount(cases.size());
        run.setStatus("RUNNING");
        runMapper.insert(run);
        return run;
    }

    private String resolveGitCommit() {
        try {
            Process p = new ProcessBuilder("git", "describe", "--always", "--dirty")
                    .directory(new java.io.File(System.getProperty("user.dir")))
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
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
        deltas.put("unseenRecommendationMatchRate", round(rate(current.getUnseenRecommendationMatchedCount(), current.getUnseenRecommendationExpectedCount())
                - rate(baseline.getUnseenRecommendationMatchedCount(), baseline.getUnseenRecommendationExpectedCount())));
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
        // 用例可能来自 JSONL（虚拟 ID），从 Loader 加载而非数据库按 ID 查，避免虚拟 ID 与数据库 ID 冲突
        Map<Long, AiConversationEvaluationCase> casesById = datasetLoader.loadCases(run.getDatasetVersion()).stream()
                .collect(Collectors.toMap(AiConversationEvaluationCase::getId, item -> item));
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
        failureCounts.put("recovery", (int) results.stream().filter(item -> Boolean.FALSE.equals(item.getRecoveryMatched())).count());
        failureCounts.put("workingMemory", (int) results.stream().filter(item -> Boolean.FALSE.equals(item.getMemoryMatched())).count());
        failureCounts.put("unseenRecommendations", (int) results.stream()
                .filter(item -> Boolean.FALSE.equals(item.getUnseenRecommendationsMatched())).count());
        failureCounts.put("execution", (int) results.stream().filter(this::unexpectedError).count());
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
            List<EvaluationTurnSnapshot> turnSnapshots = new ArrayList<>();
            List<Map<String, Object>> assertionFailures = new ArrayList<>();
            String finalStatus = null;
            int actualErrorCount = 0;
            boolean afterError = false;
            List<String> recoveryRoutes = new ArrayList<>();
            for (int turnIndex = 0; turnIndex < turns.size(); turnIndex++) {
                Map<String, Object> turn = turns.get(turnIndex);
                int turnNo = turnIndex + 1;
                ChatMessageRequest request = new ChatMessageRequest();
                request.setChatId(chatId);
                request.setMessage(String.valueOf(turn.get("message")));
                if (turn.get("selectedOptionId") != null) request.setSelectedOptionId(String.valueOf(turn.get("selectedOptionId")));
                applyLocation(turn.get("location"), request);
                ChatMessageResponse response;
                beginModelObservation();
                final Map<String, Long> stageStartedAt = new LinkedHashMap<>();
                final List<Map<String, Object>> stageTrace = new ArrayList<>();
                try {
                    response = chatOrchestrationService.chat(request, null,
                            event -> collectStageTrace(event, stageStartedAt, stageTrace));
                } catch (Exception turnError) {
                    actualErrorCount++;
                    afterError = true;
                    routes.add("ERROR");
                    contextRewrites.add(null);
                    recommendationSnapshots.add(Collections.emptyList());
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("route", "ERROR");
                    output.put("error", compact(turnError.getMessage()));
                    output.put("stages", stageTrace);
                    output.put("modelCalls", modelCallObservationSnapshot());
                    outputs.add(output);
                    turnSnapshots.add(EvaluationTurnSnapshot.error(turnNo));
                    clearModelObservation();
                    continue;
                }
                routes.add(response.getRoute());
                if (afterError) recoveryRoutes.add(response.getRoute());
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
                turnSnapshots.add(captureTurnSnapshot(turnNo, chatId, response,
                        recommendationSnapshots.get(recommendationSnapshots.size() - 1)));
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("route", response.getRoute());
                output.put("decisionStatus", response.getDecisionStatus());
                output.put("answer", compact(response.getAnswer()));
                output.put("contextRewrite", compactContextRewrite(response.getContextRewrite()));
                output.put("traceIncomplete", Boolean.TRUE.equals(response.getTraceIncomplete()));
                output.put("stages", stageTrace);
                output.put("modelCalls", modelCallObservationSnapshot());
                outputs.add(output);
                clearModelObservation();
            }
            List<String> expectedRoutes = objectMapper.readValue(evaluationCase.getExpectedRoutesJson(), new TypeReference<List<String>>() { });
            result.setActualRoutesJson(objectMapper.writeValueAsString(routes));
            result.setActualContextRewritesJson(objectMapper.writeValueAsString(compactContextRewrites(contextRewrites)));
            result.setActualFinalStatus(finalStatus);
            result.setRecommendedShopIds(finalShopIds.stream().distinct().map(String::valueOf).collect(Collectors.joining(",")));
            result.setRouteMatched(expectedRoutes.equals(routes));
            result.setActualErrorCount(actualErrorCount);
            result.setRecoveryMatched(matchesRecovery(evaluationCase.getExpectedErrorCount(), actualErrorCount,
                    evaluationCase.getExpectedRecoveryRoutesJson(), recoveryRoutes));
            boolean finalMemoryMatched = matchesMemory(evaluationCase.getExpectedMemoryJson(), chatId);
            result.setUnseenRecommendationsMatched(matchesUnseenRecommendations(
                    evaluationCase.getExpectedUnseenFromTurn(), evaluationCase.getExpectedUnseenPairsJson(), recommendationSnapshots));
            ContextRewriteCoverage rewriteCoverage = evaluateContextRewriteCoverage(
                    evaluationCase.getExpectedContextRewritesJson(), contextRewrites, recommendationSnapshots);
            result.setContextRewriteMatched(rewriteCoverage.matched);
            result.setExpectedContextRewriteCount(rewriteCoverage.expectedCount);
            result.setMatchedContextRewriteCount(rewriteCoverage.matchedCount);
            List<AiAgentToolCall> actualToolCalls = toolCalls(decisionSessionIds);
            attachToolCalls(turnSnapshots, actualToolCalls);
            List<String> actualTools = actualToolCalls.stream().map(AiAgentToolCall::getToolName).collect(Collectors.toList());
            result.setActualToolNamesJson(objectMapper.writeValueAsString(actualTools));
            result.setActualToolCallsJson(objectMapper.writeValueAsString(compactToolCalls(actualToolCalls)));
            result.setActualRecommendationSnapshotsJson(objectMapper.writeValueAsString(
                    compactRecommendationSnapshots(recommendationSnapshots)));
            ToolCoverage toolCoverage = evaluateToolCoverage(evaluationCase.getExpectedToolNamesJson(), actualTools);
            result.setExpectedToolCount(toolCoverage.expectedCount);
            result.setCoveredToolCount(toolCoverage.coveredCount);
            result.setUnexpectedToolCount(toolCoverage.unexpectedCount);
            boolean turnToolsMatched = matchesToolsByTurn(evaluationCase.getExpectedToolsByTurnJson(), turnSnapshots, assertionFailures);
            result.setToolMatched(toolCoverage.matched && turnToolsMatched);
            result.setToolArgumentsMatched(toolArgumentsMatched(evaluationCase.getExpectedToolArgumentsJson(), actualToolCalls));
            boolean turnStatesMatched = matchesTurnStates(evaluationCase.getExpectedTurnStatesJson(), turnSnapshots, assertionFailures);
            boolean relationsMatched = matchesRelations(evaluationCase.getExpectedRelationsJson(), turnSnapshots, assertionFailures);
            result.setMemoryMatched(finalMemoryMatched && turnStatesMatched && relationsMatched);
            populateModelMetrics(result, decisionSessionIds);
            result.setLocalityMatched(matchesExpectedCity(evaluationCase.getExpectedCity(), finalShopIds));
            result.setFinalStatusMatched(equalsExpected(evaluationCase.getExpectedFinalStatus(), finalStatus));
            result.setShopMatched(expectedShopsMatched(evaluationCase.getExpectedShopIds(), finalShopIds));
            Map<String, Object> turnTrace = new LinkedHashMap<>();
            turnTrace.put("turns", outputs);
            turnTrace.put("snapshots", compactTurnSnapshots(turnSnapshots));
            turnTrace.put("assertionFailures", assertionFailures);
            result.setTurnOutputsJson(objectMapper.writeValueAsString(turnTrace));
        } catch (Exception e) {
            result.setRouteMatched(false);
            result.setContextRewriteMatched(false);
            result.setToolMatched(false);
            result.setLocalityMatched(false);
            result.setFinalStatusMatched(false);
            result.setShopMatched(false);
            result.setRecoveryMatched(false);
            result.setMemoryMatched(false);
            result.setUnseenRecommendationsMatched(false);
            result.setErrorMessage(compact(e.getMessage()));
        }
        result.setDurationMs(System.currentTimeMillis() - startedAt);
        return result;
    }

    private void collectStageTrace(ChatStreamEventData event, Map<String, Long> startedAt,
                                   List<Map<String, Object>> trace) {
        if (event == null || !"node_status".equals(event.getEventName())) return;
        String node = event.getMetadata() == null ? null : String.valueOf(event.getMetadata().get("node"));
        if (node == null || "null".equals(node)) return;
        long now = System.currentTimeMillis();
        if ("running".equals(event.getStatus())) {
            startedAt.put(node, now);
            return;
        }
        if (!"success".equals(event.getStatus())) return;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("node", node);
        item.put("stageLatencyMs", Math.max(0L, now - startedAt.getOrDefault(node, now)));
        item.put("metadata", event.getMetadata());
        trace.add(item);
    }

    /** Captures an evaluation-only projection; it never changes production Working Memory. */
    private EvaluationTurnSnapshot captureTurnSnapshot(int turnNo, String chatId, ChatMessageResponse response,
                                                        List<DecisionRecommendation> recommendations) {
        EvaluationTurnSnapshot snapshot = new EvaluationTurnSnapshot();
        snapshot.turnNo = turnNo;
        snapshot.route = response == null ? null : response.getRoute();
        snapshot.finalStatus = response == null ? null : response.getDecisionStatus();
        snapshot.recommendations = recommendationIds(recommendations);
        snapshot.decisionSessionId = response == null ? null : response.getDecisionSessionId();
        if (conversationStateService == null) return snapshot;
        AiChatSession state = conversationStateService.getOrCreate(chatId);
        ConversationWorkingMemory memory = conversationStateService.workingMemory(state);
        snapshot.workingMemoryVersion = state.getVersion();
        snapshot.activeCriteria = objectMapper.convertValue(memory.getActiveCriteria(), new TypeReference<Map<String, Object>>() { });
        snapshot.activeTaskId = memory.getActiveTaskId();
        snapshot.taskCount = memory.getTasks().size();
        snapshot.candidatePool = recommendationIds(memory.getCandidatePool());
        snapshot.shownShopIds = new ArrayList<>(memory.getShownShopIds());
        snapshot.focusedShopId = memory.getFocusedShopId();
        snapshot.activeDecisionSessionId = memory.getActiveDecisionSessionId();
        snapshot.sourceDecisionSessionId = memory.getSourceDecisionSessionId();
        snapshot.dialogPhase = memory.getDialogPhase();
        return snapshot;
    }

    private List<Long> recommendationIds(List<DecisionRecommendation> recommendations) {
        if (recommendations == null) return new ArrayList<>();
        return recommendations.stream().map(DecisionRecommendation::getShopId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toList());
    }

    private void attachToolCalls(List<EvaluationTurnSnapshot> snapshots, List<AiAgentToolCall> calls) {
        for (AiAgentToolCall call : calls) {
            if (call.getTurnNo() == null || call.getTurnNo() < 1 || call.getTurnNo() > snapshots.size()) continue;
            snapshots.get(call.getTurnNo() - 1).toolCalls.add(compactToolCall(call));
        }
    }

    private Map<String, Object> compactToolCall(AiAgentToolCall call) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", call.getToolName());
        value.put("turnNo", call.getTurnNo());
        value.put("status", call.getStatus());
        try {
            value.put("arguments", call.getToolInputJson() == null || call.getToolInputJson().trim().isEmpty()
                    ? Collections.emptyMap() : objectMapper.readValue(call.getToolInputJson(), new TypeReference<Map<String, Object>>() { }));
        } catch (Exception ignored) {
            value.put("arguments", Collections.emptyMap());
        }
        return value;
    }

    private boolean matchesTurnStates(String expectedJson, List<EvaluationTurnSnapshot> snapshots,
                                      List<Map<String, Object>> failures) throws Exception {
        if (expectedJson == null || expectedJson.trim().isEmpty()) return true;
        List<Map<String, Object>> expectations = objectMapper.readValue(expectedJson, new TypeReference<List<Map<String, Object>>>() { });
        boolean matched = true;
        for (Map<String, Object> expectation : expectations) {
            Integer turn = integerValue(expectation.get("turn"));
            EvaluationTurnSnapshot snapshot = snapshotAt(snapshots, turn);
            Object rawMemory = expectation.get("memory");
            if (turn == null || snapshot == null || !(rawMemory instanceof Map)) {
                failures.add(assertionFailure(turn, null, "turnState", rawMemory, null, "TURN_SNAPSHOT_UNAVAILABLE"));
                matched = false;
                continue;
            }
            Map<?, ?> memory = (Map<?, ?>) rawMemory;
            Map<String, Object> actualMemory = snapshot.memoryProjection();
            for (Map.Entry<?, ?> entry : memory.entrySet()) {
                String path = String.valueOf(entry.getKey());
                PathValue actual = resolvePath(actualMemory, path);
                if (!matchesPathAssertion(entry.getValue(), actual)) {
                    failures.add(assertionFailure(turn, path, assertionType(entry.getValue()), entry.getValue(),
                            actual.present ? actual.value : null, actual.present ? null : "PATH_ABSENT"));
                    matched = false;
                }
            }
        }
        return matched;
    }

    private boolean matchesToolsByTurn(String expectedJson, List<EvaluationTurnSnapshot> snapshots,
                                       List<Map<String, Object>> failures) throws Exception {
        if (expectedJson == null || expectedJson.trim().isEmpty()) return true;
        List<Map<String, Object>> expectations = objectMapper.readValue(expectedJson, new TypeReference<List<Map<String, Object>>>() { });
        boolean matched = true;
        for (Map<String, Object> expectation : expectations) {
            Integer turn = integerValue(expectation.get("turn"));
            EvaluationTurnSnapshot snapshot = snapshotAt(snapshots, turn);
            Object rawTools = expectation.get("tools");
            if (turn == null || snapshot == null || !(rawTools instanceof List)) {
                failures.add(assertionFailure(turn, "toolCalls", "tool", rawTools, null, "TURN_SNAPSHOT_UNAVAILABLE"));
                matched = false;
                continue;
            }
            for (Object rawTool : (List<?>) rawTools) {
                if (!(rawTool instanceof Map) || !matchesExpectedTool((Map<?, ?>) rawTool, snapshot.toolCalls, snapshots)) {
                    failures.add(assertionFailure(turn, "toolCalls", "tool", rawTool, snapshot.toolCalls, null));
                    matched = false;
                }
            }
        }
        return matched;
    }

    private boolean matchesExpectedTool(Map<?, ?> expected, List<Map<String, Object>> actualTools,
                                        List<EvaluationTurnSnapshot> snapshots) {
        Object name = expected.get("name");
        Object expectedArguments = expected.get("arguments");
        Long expectedCandidateShopId = candidateShopId(expected.get("candidateFrom"), snapshots);
        for (Map<String, Object> actual : actualTools) {
            if (!java.util.Objects.equals(String.valueOf(name), String.valueOf(actual.get("name")))) continue;
            if (expectedArguments instanceof Map && !containsMap((Map<?, ?>) expectedArguments, actual.get("arguments"))) continue;
            if (expected.containsKey("candidateFrom")
                    && !java.util.Objects.equals(String.valueOf(expectedCandidateShopId),
                    String.valueOf(toolShopId(actual.get("arguments"))))) continue;
            return true;
        }
        return false;
    }

    private Long candidateShopId(Object candidateFrom, List<EvaluationTurnSnapshot> snapshots) {
        if (!(candidateFrom instanceof Map)) return null;
        Map<?, ?> source = (Map<?, ?>) candidateFrom;
        Integer turn = integerValue(source.get("turn"));
        Integer ordinal = integerValue(source.get("ordinal"));
        EvaluationTurnSnapshot snapshot = snapshotAt(snapshots, turn);
        if (snapshot == null || ordinal == null || ordinal < 1 || ordinal > snapshot.recommendations.size()) return null;
        return snapshot.recommendations.get(ordinal - 1);
    }

    private Long toolShopId(Object arguments) {
        if (!(arguments instanceof Map)) return null;
        Object shopId = ((Map<?, ?>) arguments).get("shopId");
        if (shopId instanceof Number) return ((Number) shopId).longValue();
        try {
            return shopId == null ? null : Long.valueOf(String.valueOf(shopId));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean containsMap(Map<?, ?> expected, Object actual) {
        if (!(actual instanceof Map)) return false;
        Map<?, ?> actualMap = (Map<?, ?>) actual;
        for (Map.Entry<?, ?> entry : expected.entrySet()) {
            Object actualValue = actualMap.get(entry.getKey());
            if (entry.getValue() instanceof Map) {
                if (!containsMap((Map<?, ?>) entry.getValue(), actualValue)) return false;
            } else if (!java.util.Objects.equals(String.valueOf(entry.getValue()), String.valueOf(actualValue))) return false;
        }
        return true;
    }

    private boolean matchesRelations(String expectedJson, List<EvaluationTurnSnapshot> snapshots,
                                     List<Map<String, Object>> failures) throws Exception {
        if (expectedJson == null || expectedJson.trim().isEmpty()) return true;
        List<Map<String, Object>> expectations = objectMapper.readValue(expectedJson, new TypeReference<List<Map<String, Object>>>() { });
        boolean matched = true;
        for (Map<String, Object> expectation : expectations) {
            Integer fromTurn = integerValue(expectation.get("fromTurn"));
            Integer toTurn = integerValue(expectation.get("toTurn"));
            String type = stringValue(expectation.get("type"));
            String relation = stringValue(expectation.get("relation"));
            EvaluationTurnSnapshot from = snapshotAt(snapshots, fromTurn);
            EvaluationTurnSnapshot to = snapshotAt(snapshots, toTurn);
            boolean relationMatched = from != null && to != null && relationMatches(type, relation, from, to);
            if (!relationMatched) {
                Map<String, Object> actual = new LinkedHashMap<>();
                actual.put("from", from == null ? null : from.relationProjection());
                actual.put("to", to == null ? null : to.relationProjection());
                failures.add(assertionFailure(toTurn, type, "relation", expectation, actual,
                        from == null || to == null ? "TURN_SNAPSHOT_UNAVAILABLE" : null));
                matched = false;
            }
        }
        return matched;
    }

    private boolean relationMatches(String type, String relation, EvaluationTurnSnapshot from, EvaluationTurnSnapshot to) {
        if ("candidatePool".equals(type)) {
            if ("INVALIDATED".equals(relation)) return !from.candidatePool.isEmpty() && to.candidatePool.isEmpty();
            if ("PRESERVED".equals(relation)) return from.candidatePool.equals(to.candidatePool);
        }
        if ("recommendations".equals(type) && "DISJOINT".equals(relation)) {
            return !from.recommendations.isEmpty() && !to.recommendations.isEmpty()
                    && Collections.disjoint(from.recommendations, to.recommendations);
        }
        if ("focusedShop".equals(type)) {
            if ("CHANGED".equals(relation)) return !java.util.Objects.equals(from.focusedShopId, to.focusedShopId);
            if ("PRESERVED".equals(relation)) return java.util.Objects.equals(from.focusedShopId, to.focusedShopId);
        }
        if ("decisionSession".equals(type)) {
            Long fromId = from.activeDecisionSessionId == null ? from.decisionSessionId : from.activeDecisionSessionId;
            Long toId = to.activeDecisionSessionId == null ? to.decisionSessionId : to.activeDecisionSessionId;
            if ("SAME".equals(relation)) return java.util.Objects.equals(fromId, toId);
            if ("CHANGED".equals(relation)) return fromId != null && toId != null && !fromId.equals(toId);
        }
        return false;
    }

    private EvaluationTurnSnapshot snapshotAt(List<EvaluationTurnSnapshot> snapshots, Integer turn) {
        return turn == null || turn < 1 || turn > snapshots.size() ? null : snapshots.get(turn - 1);
    }

    private PathValue resolvePath(Map<String, Object> root, String path) {
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map) || !((Map<?, ?>) current).containsKey(segment)) return PathValue.absent();
            current = ((Map<?, ?>) current).get(segment);
        }
        return PathValue.present(current);
    }

    private boolean matchesPathAssertion(Object expected, PathValue actual) {
        if (!(expected instanceof Map)) return actual.present && valuesEqual(expected, actual.value);
        Map<?, ?> expression = (Map<?, ?>) expected;
        if (expression.containsKey("equals")) return actual.present && valuesEqual(expression.get("equals"), actual.value);
        if (Boolean.TRUE.equals(expression.get("null"))) return actual.present && actual.value == null;
        if (Boolean.TRUE.equals(expression.get("absent"))) return !actual.present;
        if (Boolean.TRUE.equals(expression.get("empty"))) return actual.present && isEmpty(actual.value);
        if (expression.containsKey("contains")) return actual.present && containsValue(actual.value, expression.get("contains"));
        if (expression.containsKey("size")) return actual.present
                && java.util.Objects.equals(sizeOf(actual.value), integerValue(expression.get("size")));
        return false;
    }

    private boolean valuesEqual(Object expected, Object actual) {
        if (expected instanceof Number && actual instanceof Number) {
            return java.math.BigDecimal.valueOf(((Number) expected).doubleValue())
                    .compareTo(java.math.BigDecimal.valueOf(((Number) actual).doubleValue())) == 0;
        }
        return java.util.Objects.equals(objectMapper.convertValue(expected, Object.class), objectMapper.convertValue(actual, Object.class));
    }

    private boolean isEmpty(Object value) {
        return value == null || (value instanceof java.util.Collection && ((java.util.Collection<?>) value).isEmpty())
                || (value instanceof Map && ((Map<?, ?>) value).isEmpty()) || (value instanceof String && ((String) value).isEmpty());
    }

    private boolean containsValue(Object value, Object expected) {
        if (value instanceof java.util.Collection) return ((java.util.Collection<?>) value).stream().anyMatch(item -> valuesEqual(expected, item));
        if (value instanceof String) return ((String) value).contains(String.valueOf(expected));
        if (value instanceof Map && expected instanceof Map) return containsMap((Map<?, ?>) expected, value);
        return false;
    }

    private Integer sizeOf(Object value) {
        if (value instanceof java.util.Collection) return ((java.util.Collection<?>) value).size();
        if (value instanceof Map) return ((Map<?, ?>) value).size();
        if (value instanceof String) return ((String) value).length();
        return null;
    }

    private String assertionType(Object expected) {
        if (!(expected instanceof Map)) return "equals";
        for (String operation : java.util.Arrays.asList("equals", "null", "absent", "empty", "contains", "size")) {
            if (((Map<?, ?>) expected).containsKey(operation)) return operation;
        }
        return "invalid";
    }

    private Map<String, Object> assertionFailure(Integer turnNo, String path, String type, Object expected,
                                                  Object actual, String reason) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("turnNo", turnNo); failure.put("path", path); failure.put("assertionType", type);
        failure.put("expected", expected); failure.put("actual", actual);
        if (reason != null) failure.put("reason", reason);
        return failure;
    }

    private List<Map<String, Object>> compactTurnSnapshots(List<EvaluationTurnSnapshot> snapshots) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (EvaluationTurnSnapshot snapshot : snapshots) result.add(snapshot.diagnosticProjection());
        return result;
    }

    private void beginModelObservation() {
        if (modelCallObservationService != null) modelCallObservationService.begin();
    }

    private List<Map<String, Object>> modelCallObservationSnapshot() {
        return modelCallObservationService == null ? Collections.emptyList() : modelCallObservationService.snapshot();
    }

    private void clearModelObservation() {
        if (modelCallObservationService != null) modelCallObservationService.clear();
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
                || Boolean.FALSE.equals(result.getRecoveryMatched())
                || Boolean.FALSE.equals(result.getMemoryMatched())
                || Boolean.FALSE.equals(result.getUnseenRecommendationsMatched())
                || unexpectedError(result);
    }

    private boolean unexpectedError(AiConversationEvaluationCaseResult result) {
        return value(result.getActualErrorCount()) > 0
                && !Boolean.TRUE.equals(result.getRecoveryMatched());
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
            diagnostic.setExpectedErrorCount(evaluationCase.getExpectedErrorCount());
            diagnostic.setExpectedRecoveryRoutesJson(evaluationCase.getExpectedRecoveryRoutesJson());
            diagnostic.setExpectedMemoryJson(evaluationCase.getExpectedMemoryJson());
            diagnostic.setExpectedUnseenFromTurn(evaluationCase.getExpectedUnseenFromTurn());
            diagnostic.setExpectedUnseenPairsJson(evaluationCase.getExpectedUnseenPairsJson());
        }
        diagnostic.setActualRoutesJson(result.getActualRoutesJson());
        diagnostic.setActualContextRewritesJson(result.getActualContextRewritesJson());
        diagnostic.setActualToolNamesJson(result.getActualToolNamesJson());
        diagnostic.setActualToolCallsJson(result.getActualToolCallsJson());
        diagnostic.setActualRecommendationSnapshotsJson(result.getActualRecommendationSnapshotsJson());
        diagnostic.setActualFinalStatus(result.getActualFinalStatus());
        diagnostic.setRecommendedShopIds(result.getRecommendedShopIds());
        diagnostic.setRouteMatched(result.getRouteMatched());
        diagnostic.setContextRewriteMatched(result.getContextRewriteMatched());
        diagnostic.setToolMatched(result.getToolMatched());
        diagnostic.setToolArgumentsMatched(result.getToolArgumentsMatched());
        diagnostic.setLocalityMatched(result.getLocalityMatched());
        diagnostic.setFinalStatusMatched(result.getFinalStatusMatched());
        diagnostic.setShopMatched(result.getShopMatched());
        diagnostic.setActualErrorCount(result.getActualErrorCount());
        diagnostic.setRecoveryMatched(result.getRecoveryMatched());
        diagnostic.setMemoryMatched(result.getMemoryMatched());
        diagnostic.setUnseenRecommendationsMatched(result.getUnseenRecommendationsMatched());
        diagnostic.setTurnAssertionFailures(turnAssertionFailures(result.getTurnOutputsJson()));
        diagnostic.setDurationMs(result.getDurationMs());
        diagnostic.setErrorMessage(result.getErrorMessage());
        return diagnostic;
    }

    private List<Map<String, Object>> turnAssertionFailures(String turnOutputsJson) {
        if (turnOutputsJson == null || turnOutputsJson.trim().isEmpty()) return Collections.emptyList();
        try {
            Map<String, Object> trace = objectMapper.readValue(turnOutputsJson, new TypeReference<Map<String, Object>>() { });
            Object failures = trace.get("assertionFailures");
            if (!(failures instanceof List)) return Collections.emptyList();
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object failure : (List<?>) failures) {
                if (failure instanceof Map) result.add((Map<String, Object>) failure);
            }
            return result;
        } catch (Exception ignored) {
            // Historical rows used a list-only turnOutputsJson format.
            return Collections.emptyList();
        }
    }

    private boolean expectedShopsMatched(String expected, List<Long> actual) {
        if (expected == null || expected.trim().isEmpty()) return true;
        List<String> expectedIds = new ArrayList<>();
        Collections.addAll(expectedIds, expected.split(","));
        return actual.stream().map(String::valueOf).anyMatch(expectedIds::contains);
    }

    private Boolean matchesUnseenRecommendations(Integer expectedUnseenFromTurn, String expectedPairsJson,
                                                  List<List<DecisionRecommendation>> recommendationSnapshots) throws Exception {
        if (expectedPairsJson != null && !expectedPairsJson.trim().isEmpty()) {
            List<List<Integer>> expectedPairs = objectMapper.readValue(expectedPairsJson,
                    new TypeReference<List<List<Integer>>>() { });
            if (expectedPairs.isEmpty()) return true;
            for (List<Integer> pair : expectedPairs) {
                if (pair == null || pair.size() != 2 || !matchesUnseenRecommendationPair(pair.get(0), pair.get(1), recommendationSnapshots)) {
                    return false;
                }
            }
            return true;
        }
        return matchesUnseenRecommendations(expectedUnseenFromTurn, recommendationSnapshots);
    }

    private Boolean matchesUnseenRecommendations(Integer expectedUnseenFromTurn,
                                                  List<List<DecisionRecommendation>> recommendationSnapshots) {
        if (expectedUnseenFromTurn == null) return null;
        int priorTurnIndex = expectedUnseenFromTurn - 1;
        if (priorTurnIndex < 0 || priorTurnIndex >= recommendationSnapshots.size()) return false;
        for (int index = priorTurnIndex + 1; index < recommendationSnapshots.size(); index++) {
            if (!matchesUnseenRecommendationPair(expectedUnseenFromTurn, index + 1, recommendationSnapshots)) return false;
        }
        return true;
    }

    private boolean matchesUnseenRecommendationPair(Integer sourceTurn, Integer targetTurn,
                                                     List<List<DecisionRecommendation>> recommendationSnapshots) {
        if (sourceTurn == null || targetTurn == null) return false;
        int sourceIndex = sourceTurn - 1;
        int targetIndex = targetTurn - 1;
        if (sourceIndex < 0 || targetIndex < 0 || sourceIndex >= recommendationSnapshots.size()
                || targetIndex >= recommendationSnapshots.size()) return false;
        Set<Long> sourceShopIds = recommendationSnapshots.get(sourceIndex).stream()
                .map(DecisionRecommendation::getShopId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Set<Long> targetShopIds = recommendationSnapshots.get(targetIndex).stream()
                .map(DecisionRecommendation::getShopId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        return !sourceShopIds.isEmpty() && !targetShopIds.isEmpty()
                && targetShopIds.stream().noneMatch(sourceShopIds::contains);
    }

    private List<List<Long>> compactRecommendationSnapshots(List<List<DecisionRecommendation>> snapshots) {
        List<List<Long>> values = new ArrayList<>();
        for (List<DecisionRecommendation> snapshot : snapshots) {
            values.add(snapshot.stream().map(DecisionRecommendation::getShopId)
                    .filter(java.util.Objects::nonNull).collect(Collectors.toList()));
        }
        return values;
    }

    private List<AiAgentToolCall> toolCalls(Set<Long> sessionIds) {
        if (sessionIds.isEmpty()) return Collections.emptyList();
        if (conversationEventMapper == null) {
            return toolCallMapper.selectList(new QueryWrapper<AiAgentToolCall>().in("session_id", sessionIds).orderByAsc("id"));
        }
        if (conversationEventService != null) conversationEventService.flushNow();
        List<AiAgentToolCall> calls = new ArrayList<>();
        List<AiConversationEvent> events = conversationEventMapper.selectList(new QueryWrapper<AiConversationEvent>()
                .eq("event_type", "TOOL_CALL").orderByAsc("id"));
        for (AiConversationEvent event : events) {
            try {
                Map<String, Object> payload = objectMapper.readValue(event.getEventResult(), new TypeReference<Map<String, Object>>() { });
                Long sessionId = payload.get("decisionSessionId") == null ? null : Long.valueOf(String.valueOf(payload.get("decisionSessionId")));
                if (!sessionIds.contains(sessionId)) continue;
                AiAgentToolCall call = new AiAgentToolCall();
                call.setId(event.getId()); call.setSessionId(sessionId);
                call.setToolName(String.valueOf(payload.get("tool"))); call.setToolInputJson(String.valueOf(payload.get("arguments")));
                call.setTurnNo(payload.get("turnNo") == null ? null : Integer.valueOf(String.valueOf(payload.get("turnNo"))));
                call.setStatus(event.getStatus()); calls.add(call);
            } catch (Exception ignored) { }
        }
        return calls;
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
        long failed = results.stream().filter(this::hasFailure).count();
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
        run.setUnseenRecommendationExpectedCount((int) results.stream()
                .filter(item -> item.getUnseenRecommendationsMatched() != null).count());
        run.setUnseenRecommendationMatchedCount((int) results.stream()
                .filter(item -> Boolean.TRUE.equals(item.getUnseenRecommendationsMatched())).count());
        run.setCompletedCount((int) (results.size() - failed));
        run.setAvgDurationMs(results.isEmpty() ? 0L : Math.round(results.stream().mapToLong(AiConversationEvaluationCaseResult::getDurationMs).average().orElse(0D)));
        List<Long> durations = results.stream().map(AiConversationEvaluationCaseResult::getDurationMs).sorted().collect(Collectors.toList());
        run.setP50DurationMs(percentile(durations, 0.50D));
        run.setP95DurationMs(percentile(durations, 0.95D));
        run.setP99DurationMs(percentile(durations, 0.99D));
        run.setErrorRate(results.isEmpty() ? 0D : (double) failed / results.size());
        run.setModelCallCount(results.stream().map(AiConversationEvaluationCaseResult::getModelCallCount).filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum());
        run.setModelSuccessCount(results.stream().map(AiConversationEvaluationCaseResult::getModelSuccessCount).filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum());
        run.setModelFailureCount(results.stream().map(AiConversationEvaluationCaseResult::getModelFailureCount).filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum());
        run.setPromptTokenCount(results.stream().map(AiConversationEvaluationCaseResult::getPromptTokenCount).filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum());
        run.setCompletionTokenCount(results.stream().map(AiConversationEvaluationCaseResult::getCompletionTokenCount).filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum());
        run.setErrorSummary(failed == 0 ? null : "失败用例数=" + failed);
    }

    private void populateModelMetrics(AiConversationEvaluationCaseResult result, Set<Long> sessionIds) {
        result.setModelCallCount(0);
        result.setModelSuccessCount(0);
        result.setModelFailureCount(0);
        result.setPromptTokenCount(0L);
        result.setCompletionTokenCount(0L);
        if (sessionIds.isEmpty() || decisionMetricMapper == null) return;
        List<AiDecisionMetric> metrics = decisionMetricMapper.selectList(new QueryWrapper<AiDecisionMetric>().in("session_id", sessionIds));
        result.setModelCallCount(metrics.stream().map(AiDecisionMetric::getModelCallCount).filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum());
        result.setModelSuccessCount(metrics.stream().map(AiDecisionMetric::getModelSuccessCount).filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum());
        result.setModelFailureCount(metrics.stream().map(AiDecisionMetric::getModelFailureCount).filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum());
        result.setPromptTokenCount(metrics.stream().map(AiDecisionMetric::getPromptTokenCount).filter(java.util.Objects::nonNull).mapToLong(Integer::longValue).sum());
        result.setCompletionTokenCount(metrics.stream().map(AiDecisionMetric::getCompletionTokenCount).filter(java.util.Objects::nonNull).mapToLong(Integer::longValue).sum());
    }

    private boolean matchesRecovery(Integer expectedErrorCount, int actualErrorCount,
                                    String expectedRecoveryRoutesJson, List<String> actualRecoveryRoutes) throws Exception {
        int expected = expectedErrorCount == null ? 0 : expectedErrorCount;
        if (expected != actualErrorCount) return false;
        if (expectedRecoveryRoutesJson == null || expectedRecoveryRoutesJson.trim().isEmpty()) return true;
        List<String> expectedRoutes = objectMapper.readValue(expectedRecoveryRoutesJson, new TypeReference<List<String>>() { });
        return expectedRoutes.equals(actualRecoveryRoutes);
    }

    private boolean matchesMemory(String expectedMemoryJson, String chatId) throws Exception {
        if (expectedMemoryJson == null || expectedMemoryJson.trim().isEmpty()) return true;
        if (conversationStateService == null) return false;
        Map<String, Object> expected = objectMapper.readValue(expectedMemoryJson, new TypeReference<Map<String, Object>>() { });
        ConversationWorkingMemory memory = conversationStateService.workingMemory(conversationStateService.getOrCreate(chatId));
        String expectedSearchCity = stringValue(expected.get("searchCity"));
        if (expectedSearchCity != null && (memory.getSearchLocation() == null
                || !expectedSearchCity.equals(memory.getSearchLocation().getCity()))) return false;
        String expectedPhase = stringValue(expected.get("dialogPhase"));
        if (expectedPhase != null && !equalsExpected(expectedPhase, memory.getDialogPhase())) return false;
        if (expected.containsKey("candidatePoolEmpty")) {
            boolean expectedEmpty = Boolean.parseBoolean(String.valueOf(expected.get("candidatePoolEmpty")));
            boolean actualEmpty = memory.getCandidatePool() == null || memory.getCandidatePool().isEmpty();
            if (expectedEmpty != actualEmpty) return false;
        }
        if (expected.containsKey("candidatePoolSize")) {
            Integer expectedSize = integerValue(expected.get("candidatePoolSize"));
            int actualSize = memory.getCandidatePool() == null ? 0 : memory.getCandidatePool().size();
            if (expectedSize != null && expectedSize != actualSize) return false;
        }
        if (expected.containsKey("focusedShopIdNull")) {
            boolean expectedNull = Boolean.parseBoolean(String.valueOf(expected.get("focusedShopIdNull")));
            if (expectedNull != (memory.getFocusedShopId() == null)) return false;
        }
        String expectedCuisine = stringValue(expected.get("cuisine"));
        if (expectedCuisine != null && (memory.getActiveCriteria() == null
                || !expectedCuisine.equals(memory.getActiveCriteria().getCuisine()))) return false;
        String expectedTargetArea = stringValue(expected.get("targetArea"));
        if (expectedTargetArea != null && (memory.getActiveCriteria() == null
                || !expectedTargetArea.equals(memory.getActiveCriteria().getTargetArea()))) return false;
        if (expected.containsKey("budgetPerPerson")) {
            Integer expectedBudget = integerValue(expected.get("budgetPerPerson"));
            if (expectedBudget != null && (memory.getActiveCriteria() == null
                    || !expectedBudget.equals(memory.getActiveCriteria().getBudgetPerPerson()))) return false;
        }
        if (expected.containsKey("hardConstraintsEmpty")) {
            boolean expectedEmpty = Boolean.parseBoolean(String.valueOf(expected.get("hardConstraintsEmpty")));
            boolean actualEmpty = memory.getActiveCriteria() == null || memory.getActiveCriteria().getPreferences() == null
                    || memory.getActiveCriteria().getPreferences().isEmpty();
            if (expectedEmpty != actualEmpty) return false;
        }
        return true;
    }

    private long percentile(List<Long> values, double quantile) {
        if (values.isEmpty()) return 0L;
        int index = (int) Math.ceil(quantile * values.size()) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
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

    private static final class PathValue {
        private final boolean present;
        private final Object value;

        private PathValue(boolean present, Object value) { this.present = present; this.value = value; }
        private static PathValue present(Object value) { return new PathValue(true, value); }
        private static PathValue absent() { return new PathValue(false, null); }
    }

    /** Minimal, evaluation-local turn projection. It is deliberately not a production DTO. */
    private static final class EvaluationTurnSnapshot {
        private int turnNo;
        private String route;
        private String finalStatus;
        private Integer workingMemoryVersion;
        private Map<String, Object> activeCriteria = new LinkedHashMap<>();
        private List<Long> candidatePool = new ArrayList<>();
        private List<Long> shownShopIds = new ArrayList<>();
        private Long focusedShopId;
        private Long activeDecisionSessionId;
        private Long sourceDecisionSessionId;
        private Long decisionSessionId;
        private String dialogPhase;
        private String activeTaskId;
        private Integer taskCount;
        private List<Map<String, Object>> toolCalls = new ArrayList<>();
        private List<Long> recommendations = new ArrayList<>();

        private static EvaluationTurnSnapshot error(int turnNo) {
            EvaluationTurnSnapshot snapshot = new EvaluationTurnSnapshot();
            snapshot.turnNo = turnNo; snapshot.route = "ERROR";
            return snapshot;
        }

        private Map<String, Object> memoryProjection() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("activeCriteria", activeCriteria);
            result.put("candidatePool", candidatePool);
            result.put("shownShopIds", shownShopIds);
            result.put("focusedShopId", focusedShopId);
            result.put("sourceDecisionSessionId", sourceDecisionSessionId);
            result.put("activeDecisionSessionId", activeDecisionSessionId);
            result.put("dialogPhase", dialogPhase);
            result.put("activeTaskId", activeTaskId);
            result.put("taskCount", taskCount);
            return result;
        }

        private Map<String, Object> relationProjection() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("candidatePool", candidatePool);
            result.put("recommendations", recommendations);
            result.put("focusedShopId", focusedShopId);
            result.put("activeDecisionSessionId", activeDecisionSessionId);
            result.put("decisionSessionId", decisionSessionId);
            return result;
        }

        private Map<String, Object> diagnosticProjection() {
            Map<String, Object> result = memoryProjection();
            result.put("turnNo", turnNo); result.put("route", route); result.put("finalStatus", finalStatus);
            result.put("workingMemoryVersion", workingMemoryVersion); result.put("toolCalls", toolCalls);
            result.put("recommendations", recommendations); result.put("decisionSessionId", decisionSessionId);
            return result;
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
