package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.ChatLocationInput;
import com.hmdp.ai.dto.ChatMessageRequest;
import com.hmdp.ai.dto.ChatMessageResponse;
import com.hmdp.ai.dto.ConversationEvaluationRunResponse;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.entity.AiConversationEvaluationCase;
import com.hmdp.ai.entity.AiConversationEvaluationCaseResult;
import com.hmdp.ai.entity.AiConversationEvaluationRun;
import com.hmdp.ai.mapper.AiConversationEvaluationCaseMapper;
import com.hmdp.ai.mapper.AiConversationEvaluationCaseResultMapper;
import com.hmdp.ai.mapper.AiConversationEvaluationRunMapper;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Executes scripted user turns through the same chat entry point used by the web console. */
@Service
public class AiConversationEvaluationService {
    @Resource private ChatOrchestrationService chatOrchestrationService;
    @Resource private AiConversationEvaluationCaseMapper caseMapper;
    @Resource private AiConversationEvaluationRunMapper runMapper;
    @Resource private AiConversationEvaluationCaseResultMapper resultMapper;
    @Resource private ObjectMapper objectMapper;
    @Resource private AiProperties aiProperties;

    public ConversationEvaluationRunResponse runActiveCases() {
        String datasetVersion = "conversation-v1";
        List<AiConversationEvaluationCase> cases = caseMapper.selectList(new QueryWrapper<AiConversationEvaluationCase>()
                .eq("active", true).eq("dataset_version", datasetVersion).orderByAsc("id"));
        if (cases.isEmpty()) throw new IllegalStateException("没有启用的对话轨迹评测用例");

        AiConversationEvaluationRun run = new AiConversationEvaluationRun();
        run.setUserId(UserHolder.getUser() == null ? null : UserHolder.getUser().getId());
        run.setModel(aiProperties.getModel());
        run.setDatasetVersion(datasetVersion);
        run.setCaseCount(cases.size());
        run.setStatus("RUNNING");
        runMapper.insert(run);

        List<AiConversationEvaluationCaseResult> results = new ArrayList<>();
        for (AiConversationEvaluationCase evaluationCase : cases) {
            AiConversationEvaluationCaseResult result = evaluate(run.getId(), evaluationCase);
            resultMapper.insert(result);
            results.add(result);
        }
        finish(run, results);
        runMapper.updateById(run);

        ConversationEvaluationRunResponse response = new ConversationEvaluationRunResponse();
        response.setRun(run);
        response.setCaseResults(results);
        return response;
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

    private AiConversationEvaluationCaseResult evaluate(Long runId, AiConversationEvaluationCase evaluationCase) {
        AiConversationEvaluationCaseResult result = new AiConversationEvaluationCaseResult();
        result.setRunId(runId);
        result.setCaseId(evaluationCase.getId());
        String chatId = "eval-" + evaluationCase.getCaseCode().toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        result.setChatId(chatId);
        long startedAt = System.currentTimeMillis();
        try {
            List<Map<String, Object>> turns = objectMapper.readValue(evaluationCase.getTurnsJson(), new TypeReference<List<Map<String, Object>>>() { });
            List<String> routes = new ArrayList<>();
            List<Long> shopIds = new ArrayList<>();
            List<Map<String, Object>> outputs = new ArrayList<>();
            String finalStatus = null;
            for (Map<String, Object> turn : turns) {
                ChatMessageRequest request = new ChatMessageRequest();
                request.setChatId(chatId);
                request.setMessage(String.valueOf(turn.get("message")));
                applyLocation(turn.get("location"), request);
                ChatMessageResponse response = chatOrchestrationService.chat(request);
                routes.add(response.getRoute());
                if (response.getDecisionStatus() != null) finalStatus = response.getDecisionStatus();
                if (response.getDecision() != null) {
                    finalStatus = response.getDecision().getStatus();
                    for (DecisionRecommendation item : response.getDecision().getRecommendations()) shopIds.add(item.getShopId());
                }
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("route", response.getRoute());
                output.put("decisionStatus", response.getDecisionStatus());
                output.put("answer", compact(response.getAnswer()));
                outputs.add(output);
            }
            List<String> expectedRoutes = objectMapper.readValue(evaluationCase.getExpectedRoutesJson(), new TypeReference<List<String>>() { });
            result.setActualRoutesJson(objectMapper.writeValueAsString(routes));
            result.setActualFinalStatus(finalStatus);
            result.setRecommendedShopIds(shopIds.stream().distinct().map(String::valueOf).collect(Collectors.joining(",")));
            result.setRouteMatched(expectedRoutes.equals(routes));
            result.setFinalStatusMatched(equalsExpected(evaluationCase.getExpectedFinalStatus(), finalStatus));
            result.setShopMatched(expectedShopsMatched(evaluationCase.getExpectedShopIds(), shopIds));
            result.setTurnOutputsJson(objectMapper.writeValueAsString(outputs));
        } catch (Exception e) {
            result.setRouteMatched(false);
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
        return expected == null || expected.trim().isEmpty() || expected.equals(actual);
    }

    private boolean expectedShopsMatched(String expected, List<Long> actual) {
        if (expected == null || expected.trim().isEmpty()) return true;
        List<String> expectedIds = new ArrayList<>();
        Collections.addAll(expectedIds, expected.split(","));
        return actual.stream().map(String::valueOf).anyMatch(expectedIds::contains);
    }

    private void finish(AiConversationEvaluationRun run, List<AiConversationEvaluationCaseResult> results) {
        long failed = results.stream().filter(item -> item.getErrorMessage() != null).count();
        run.setStatus(failed == 0 ? "COMPLETED" : "COMPLETED_WITH_ERRORS");
        run.setRouteMatchedCount((int) results.stream().filter(item -> Boolean.TRUE.equals(item.getRouteMatched())).count());
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

    private String compact(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() > 300 ? normalized.substring(0, 300) + "..." : normalized;
    }
}
