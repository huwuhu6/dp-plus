package com.hmdp.ai.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.ChatMessageRequest;
import com.hmdp.ai.dto.ChatMessageResponse;
import com.hmdp.ai.dto.ConversationWorkingMemory;
import com.hmdp.ai.v2.runtime.ExecutionObservation;
import com.hmdp.ai.v2.plan.ExecutionAction;
import com.hmdp.ai.v2.semantic.TurnSemantics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** 独立文件评测账本：只读已有领域数据，不写业务库，也不决定 Agent 的执行。 */
public final class V2PilotEvaluation {
    private V2PilotEvaluation() { }
    public enum Status { SATISFIED, UNSATISFIED, UNVERIFIED, PRECONDITION_NOT_MET, NOT_APPLICABLE }
    public enum Stage { PREPARATION, FIRST_TURN_COVERAGE, BUSINESS_TURN, OBSERVATION, EVALUATOR }
    public enum Readiness { READY, NOT_READY, EVIDENCE_CONFLICT }
    public enum Mode { CONTROLLED_SEMANTICS, CONTROLLED_CONTEXT_REAL_MODEL, REAL_END_TO_END }

    public record ItemPlan(String itemId, boolean core, boolean applicable, boolean requiresReady) { }
    public record Plan(String scenarioId, String specVersion, String freezeSha, String repositorySha,
                       boolean dirtyWorkspace, Mode mode, String factBranch, List<ItemPlan> items,
                       List<String> controlledBoundaries, Map<String, String> contextFacts) {
        public Plan {
            items = List.copyOf(items); controlledBoundaries = List.copyOf(controlledBoundaries);
            contextFacts = Map.copyOf(contextFacts);
            if (!"v1.1".equals(specVersion) || freezeSha == null || !freezeSha.matches("[0-9a-f]{40}")
                    || repositorySha == null || !repositorySha.matches("[0-9a-f]{40}") || mode == null
                    || factBranch == null || factBranch.isBlank() || items.isEmpty())
                throw new IllegalArgumentException("frozen plan and provenance required");
            Set<String> ids = new HashSet<>();
            for (ItemPlan item : items)
                if (!item.itemId().matches(scenarioId + "-[MF][1-9][0-9]*") || !ids.add(item.itemId()))
                    throw new IllegalArgumentException("unique scenario item IDs required");
            if (items.stream().noneMatch(ItemPlan::core)) throw new IllegalArgumentException("core items required");
        }
    }
    public record Gate(Readiness readiness, Stage stage, List<String> evidence, String reason) {
        public Gate {
            evidence = List.copyOf(evidence);
            if (readiness == Readiness.READY && evidence.isEmpty()) throw new IllegalArgumentException("readiness evidence required");
        }
    }
    public record BoundaryCall(String actionType, JsonNode action, JsonNode observation,
                               JsonNode toolReads, JsonNode boundaryException, String source) { }
    public record TurnEvidence(int turn, JsonNode userRequest, JsonNode fullResponse,
                               JsonNode workingMemory, JsonNode parsedSemantics,
                               List<BoundaryCall> executionCalls, String executionEvidenceStatus) {
        public TurnEvidence { executionCalls = List.copyOf(executionCalls); }
    }
    public record ItemResult(String itemId, Status status, Stage stage, List<String> evidence, String reason) {
        public ItemResult { evidence = List.copyOf(evidence); }
    }
    public record ScenarioResult(Plan plan, Gate gate, List<TurnEvidence> turns, List<ItemResult> items,
                                 boolean corePassed, boolean fullyAdjudicated, boolean businessFailed) { }
    public record Metric(int numerator, int denominator, Double value) {
        static Metric of(int numerator, int denominator) {
            return new Metric(numerator, denominator, denominator == 0 ? null : (double) numerator / denominator);
        }
    }
    public record Summary(int planned, int ready, int corePassed, int fullyAdjudicated, int businessFailed,
                          Metric readinessRate, Metric readyPassRate, Metric fullCompletionRate) { }
    public record Report(String resultVersion, List<ScenarioResult> scenarios, Summary summary) { }

    /** valueToTree 复制本轮数据，后续 memory mutation 不得改写已保存的观察证据。 */
    public static TurnEvidence capture(ObjectMapper mapper, int turn, ChatMessageRequest request,
                                       ChatMessageResponse response, ConversationWorkingMemory memory,
                                       TurnSemantics semantics, List<BoundaryCall> calls, boolean observedBoundary) {
        return new TurnEvidence(turn, mapper.valueToTree(request), mapper.valueToTree(response),
                mapper.valueToTree(memory), semantics == null ? null : mapper.valueToTree(Map.of("value", semantics,
                        "requestTypes", semantics.requests().stream().map(parsedRequest -> Map.of("requestId", parsedRequest.requestId(),
                                "type", parsedRequest.getClass().getSimpleName())).toList())), calls,
                observedBoundary ? "OBSERVED_TEST_BOUNDARY" : "UNAVAILABLE");
    }

    public static BoundaryCall observedCall(ObjectMapper mapper, ExecutionAction action,
                                            ExecutionObservation observation, String source) {
        return observedCall(mapper, action, observation, source, List.of());
    }

    public static BoundaryCall observedCall(ObjectMapper mapper, ExecutionAction action,
                                            ExecutionObservation observation, String source, List<Map<String, Object>> toolReads) {
        return new BoundaryCall(action.getClass().getSimpleName(), mapper.valueToTree(action), mapper.valueToTree(observation),
                mapper.valueToTree(toolReads), null, source);
    }

    public static BoundaryCall observedFailure(ObjectMapper mapper, ExecutionAction action, RuntimeException error,
                                               String source, List<Map<String, Object>> toolReads) {
        return new BoundaryCall(action.getClass().getSimpleName(), mapper.valueToTree(action), null,
                mapper.valueToTree(toolReads), mapper.valueToTree(Map.of("type", error.getClass().getName(),
                        "message", Objects.toString(error.getMessage(), ""))), source);
    }

    /** 展示与存储必须分别取证；不能从 memory 自己生成一份“用户可见列表”来证明一致。 */
    public static Gate displayGate(TurnEvidence displayed, String taskId, String batchId,
                                   List<Long> plannedIds, Stage stage) {
        String ref = "turns/" + displayed.turn();
        JsonNode visible = displayed.fullResponse().path("decision").path("recommendations");
        List<Long> visibleIds = ids(visible, "shopId");
        if (plannedIds.isEmpty() || visibleIds.isEmpty() || !visibleIds.equals(plannedIds)
                || new HashSet<>(visibleIds).size() != visibleIds.size())
            return new Gate(Readiness.NOT_READY, stage, List.of(ref + "/fullResponse"), "planned display not established");
        JsonNode task = task(displayed, taskId);
        JsonNode batch = null;
        if (task != null) for (JsonNode item : task.path("recommendationBatches"))
            if (batchId.equals(item.path("batchId").asText())) batch = item;
        if (batch == null || !ids(batch.path("candidates"), "shopId").equals(visibleIds))
            return new Gate(Readiness.EVIDENCE_CONFLICT, stage,
                    List.of(ref + "/fullResponse", ref + "/workingMemory"), "display and stored batch identities/order conflict");
        for (int i = 0; i < visible.size(); i++)
            if (!visible.get(i).path("shopName").equals(batch.path("candidates").get(i).path("shopName")))
                return new Gate(Readiness.EVIDENCE_CONFLICT, stage, List.of(ref), "display and stored names conflict");
        return new Gate(Readiness.READY, stage, List.of(ref + "/fullResponse", ref + "/workingMemory"), "display and stored batch independently agree");
    }

    public static JsonNode task(TurnEvidence evidence, String taskId) {
        if (taskId == null) return null;
        for (JsonNode task : evidence.workingMemory().path("tasks"))
            if (taskId.equals(task.path("taskId").asText())) return task;
        return null;
    }

    public static List<Long> ids(JsonNode nodes, String field) {
        List<Long> ids = new ArrayList<>();
        if (nodes != null && nodes.isArray()) for (JsonNode node : nodes)
            if (node.path(field).isIntegralNumber()) ids.add(node.path(field).longValue());
        return List.copyOf(ids);
    }

    /** check=null 是没有执行；无证据的 true 也不可信。前提与适用性由事前 plan/gate 决定。 */
    public static ItemResult check(ItemPlan item, Gate gate, Boolean checked,
                                   List<String> evidence, String reason, Stage stage) {
        if (!item.applicable()) return new ItemResult(item.itemId(), Status.NOT_APPLICABLE, Stage.PREPARATION, List.of(), "frozen as not applicable");
        if (item.requiresReady() && gate.readiness() != Readiness.READY)
            return new ItemResult(item.itemId(), Status.PRECONDITION_NOT_MET, gate.stage(), gate.evidence(), gate.reason());
        if (checked == null || evidence.isEmpty())
            return new ItemResult(item.itemId(), Status.UNVERIFIED, stage, evidence, reason);
        return new ItemResult(item.itemId(), checked ? Status.SATISFIED : Status.UNSATISFIED, stage, evidence, reason);
    }

    public static ScenarioResult finish(Plan plan, Gate gate, List<TurnEvidence> turns, List<ItemResult> supplied) {
        Map<String, ItemResult> indexed = new LinkedHashMap<>();
        for (ItemResult item : supplied) {
            ItemPlan declared = plan.items().stream().filter(p -> p.itemId().equals(item.itemId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unplanned item"));
            if (indexed.containsKey(item.itemId())) throw new IllegalArgumentException("duplicate item result");
            // 文件输入也不得绕过门控或把空 evidence 的 SATISFIED 写成成功。
            ItemResult validated = item.status() == Status.SATISFIED || item.status() == Status.UNSATISFIED
                    ? check(declared, gate, item.status() == Status.SATISFIED, item.evidence(), item.reason(), item.stage()) : item;
            if (validated.status() == Status.NOT_APPLICABLE && declared.applicable())
                throw new IllegalArgumentException("cannot change applicability after execution");
            indexed.put(item.itemId(), validated);
        }
        List<ItemResult> results = plan.items().stream().map(item -> indexed.getOrDefault(item.itemId(),
                check(item, gate, null, List.of(), "business check not executed", Stage.OBSERVATION))).toList();
        boolean failed = results.stream().anyMatch(item -> item.status() == Status.UNSATISFIED);
        boolean corePassed = gate.readiness() == Readiness.READY && !failed && plan.items().stream().filter(ItemPlan::core)
                .allMatch(item -> indexedStatus(results, item.itemId()) == Status.SATISFIED
                        || !item.applicable() && indexedStatus(results, item.itemId()) == Status.NOT_APPLICABLE);
        boolean full = results.stream().allMatch(item -> Set.of(Status.SATISFIED, Status.UNSATISFIED, Status.NOT_APPLICABLE).contains(item.status()));
        return new ScenarioResult(plan, gate, List.copyOf(turns), results, corePassed, full, failed);
    }

    private static Status indexedStatus(List<ItemResult> results, String id) {
        return results.stream().filter(item -> id.equals(item.itemId())).findFirst().orElseThrow().status();
    }

    public static Report report(List<Plan> planned, List<ScenarioResult> supplied) {
        if (planned.stream().map(Plan::scenarioId).distinct().count() != planned.size())
            throw new IllegalArgumentException("unique planned scenarios required");
        List<ScenarioResult> scenarios = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ScenarioResult result : supplied) {
            if (!seen.add(result.plan().scenarioId()) || !planned.contains(result.plan()))
                throw new IllegalArgumentException("duplicate or changed frozen plan");
        }
        // 清单先冻结；没有运行的计划场景仍进入分母，不能挑选就绪结果提高分数。
        for (Plan plan : planned) {
            ScenarioResult result = supplied.stream().filter(s -> s.plan().equals(plan)).findFirst().orElse(null);
            scenarios.add(result == null ? finish(plan,
                    new Gate(Readiness.NOT_READY, Stage.PREPARATION, List.of(), "planned scenario not run"), List.of(), List.of())
                    : finish(plan, result.gate(), result.turns(), result.items()));
        }
        if (scenarios.stream().map(s -> s.plan().scenarioId()).distinct().count() != scenarios.size())
            throw new IllegalArgumentException("one result per planned scenario required");
        if (scenarios.stream().map(s -> s.plan().mode()).distinct().count() > 1)
            throw new IllegalArgumentException("controlled and end-to-end scores must stay separate");
        int ready = (int) scenarios.stream().filter(s -> s.gate().readiness() == Readiness.READY).count();
        int passed = (int) scenarios.stream().filter(ScenarioResult::corePassed).count();
        int full = (int) scenarios.stream().filter(ScenarioResult::fullyAdjudicated).count();
        int failed = (int) scenarios.stream().filter(ScenarioResult::businessFailed).count();
        return new Report("v2-pilot-result-v1", List.copyOf(scenarios), new Summary(scenarios.size(), ready, passed,
                full, failed, Metric.of(ready, scenarios.size()), Metric.of(passed, ready), Metric.of(full, scenarios.size())));
    }

    public static void write(ObjectMapper mapper, Path path, Report report) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), report);
    }
}
