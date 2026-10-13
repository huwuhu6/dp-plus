package com.hmdp.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.entity.AiConversationEvaluationCase;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fail-fast structural contract for audited V2 datasets; it intentionally does not inspect holdout semantics. */
public final class V2DatasetValidator {
    private static final Set<String> OPERATORS = Set.of("equals", "null", "notSet", "absent", "empty", "nonEmpty", "contains", "size");
    private static final Map<String, Set<String>> RELATIONS = Map.of(
            "candidatePool", Set.of("INVALIDATED", "PRESERVED"),
            "recommendations", Set.of("DISJOINT"),
            "focusedShop", Set.of("CHANGED", "PRESERVED"),
            "decisionSession", Set.of("SAME", "CHANGED"),
            "groundedOrdinal", Set.of("EQUALS_VISIBLE"));
    private static final Set<String> PATHS = Set.of("taskId", "taskLifecycle", "affectedTaskId", "affectedTaskLifecycle", "criteria", "relativePreferences", "searchAnchor", "currentVisibleShopIds", "groundedEntities", "feedbackLedger", "rejectedShopIds", "relaxable", "locked", "selectedShopId", "executedActions", "conditionalCriteriaApplied", "finalCandidates", "verifiedCandidateIds", "verificationStatus", "hardConstraintsVerified", "verificationFailures", "replanned", "staleSuppressed", "decisionStatus", "semantic");
    private V2DatasetValidator() { }

    public static void validate(String version, List<AiConversationEvaluationCase> cases, ObjectMapper mapper) {
        if (!requiresJsonl(version)) return;
        if (cases.isEmpty()) throw new IllegalArgumentException("V2 dataset requires active cases");
        Set<String> codes = new HashSet<>();
        for (AiConversationEvaluationCase item : cases) {
            if (!Boolean.TRUE.equals(item.getActive()) || item.getCaseCode() == null || item.getCaseCode().isBlank() || !codes.add(item.getCaseCode()))
                throw new IllegalArgumentException("V2 dataset requires unique active caseCode");
            if (!version.equals(item.getDatasetVersion())) throw new IllegalArgumentException("datasetVersion mismatch: " + item.getCaseCode());
            try {
                List<Map<String, Object>> turns = mapper.readValue(item.getTurnsJson(), new TypeReference<>() { });
                if (turns.isEmpty()) throw new IllegalArgumentException("V2 dataset requires non-empty turns: " + item.getCaseCode());
                for (Map<String, Object> turn : turns) {
                    if (!(turn.get("message") instanceof String message) || message.isBlank())
                        throw new IllegalArgumentException("turn requires non-empty message");
                }
                if (item.getExpectedFinalStatus() != null && item.getExpectedFinalStatus().contains("|"))
                    throw new IllegalArgumentException("V2 dataset forbids ambiguous final status: " + item.getCaseCode());
                if (item.getExpectedV2OutcomesJson() != null) for (Map<String, Object> outcome : mapper.readValue(item.getExpectedV2OutcomesJson(), new TypeReference<List<Map<String, Object>>>() { })) {
                    requireTurn(outcome.get("turn"), turns.size());
                    Object raw = outcome.get("assertions");
                    if (!(raw instanceof Map<?, ?> assertions) || assertions.isEmpty()) throw new IllegalArgumentException("outcome requires non-empty assertions");
                    for (Map.Entry<?, ?> assertion : assertions.entrySet()) {
                        if (!knownPath(String.valueOf(assertion.getKey()))) throw new IllegalArgumentException("unknown assertion path");
                        validateExpression(assertion.getValue());
                    }
                }
                if (item.getExpectedTurnStatesJson() != null) for (Map<String, Object> state : mapper.readValue(item.getExpectedTurnStatesJson(), new TypeReference<List<Map<String, Object>>>() { })) {
                    requireTurn(state.get("turn"), turns.size());
                    if (!(state.get("memory") instanceof Map<?, ?> memory) || memory.isEmpty()) throw new IllegalArgumentException("state requires non-empty memory assertions");
                    for (Map.Entry<?, ?> assertion : memory.entrySet()) {
                        if (!knownMemoryPath(String.valueOf(assertion.getKey()))) throw new IllegalArgumentException("unknown memory assertion path");
                        validateExpression(assertion.getValue());
                    }
                }
                if (item.getExpectedRelationsJson() != null) for (Map<String, Object> relation : mapper.readValue(item.getExpectedRelationsJson(), new TypeReference<List<Map<String, Object>>>() { })) {
                    requireTurn(relation.get("fromTurn"), turns.size()); requireTurn(relation.get("toTurn"), turns.size());
                    Number from = (Number) relation.get("fromTurn"); Number to = (Number) relation.get("toTurn");
                    if (from == null || to == null || from.intValue() < 1 || to.intValue() < 1 || from.intValue() >= to.intValue() || to.intValue() > turns.size())
                        throw new IllegalArgumentException("invalid cross-turn relation");
                    String type = String.valueOf(relation.get("type"));
                    String expected = String.valueOf(relation.get("relation"));
                    if (!RELATIONS.getOrDefault(type, Set.of()).contains(expected))
                        throw new IllegalArgumentException("unknown cross-turn relation");
                    if ("groundedOrdinal".equals(relation.get("type"))) {
                        Object ordinal = relation.get("ordinal");
                        boolean validOrdinal = isInteger(ordinal) && ((Number) ordinal).intValue() >= 1
                                || "LAST".equals(ordinal);
                        if (!"EQUALS_VISIBLE".equals(relation.get("relation")) || !validOrdinal)
                            throw new IllegalArgumentException("invalid grounded ordinal relation");
                    }
                }
            } catch (Exception e) { throw new IllegalArgumentException("invalid V2 dataset case: " + item.getCaseCode(), e); }
        }
    }

    // V2 是执行契约，不能由数据集末尾的修订号决定是否校验或是否回退 MySQL。
    static boolean requiresJsonl(String version) {
        return version.startsWith("conversation-v2-") || version.endsWith("-v2");
    }

    private static boolean isInteger(Object value) {
        return value instanceof Integer || value instanceof Long;
    }

    private static void requireTurn(Object value, int size) {
        if (!isInteger(value) || ((Number) value).longValue() < 1 || ((Number) value).longValue() > size)
            throw new IllegalArgumentException("invalid expected turn");
    }

    static void validateExpression(Object value) {
        if (!(value instanceof Map<?, ?> expression)) return;
        if (expression.size() != 1) throw new IllegalArgumentException("invalid assertion operator");
        String operator = String.valueOf(expression.keySet().iterator().next());
        Object operand = expression.get(operator);
        if (!OPERATORS.contains(operator)) throw new IllegalArgumentException("invalid assertion operator");
        if (Set.of("null", "notSet", "absent", "empty", "nonEmpty").contains(operator) && !Boolean.TRUE.equals(operand))
            throw new IllegalArgumentException("assertion flag must be true");
        if ("size".equals(operator) && (!isInteger(operand) || ((Number) operand).longValue() < 0))
            throw new IllegalArgumentException("assertion size must be non-negative integer");
    }

    private static boolean knownMemoryPath(String path) {
        if (path.startsWith("activeCriteria.")) return knownPath("criteria." + path.substring("activeCriteria.".length()))
                || path.matches("activeCriteria\\.(targetProvince|targetCity|targetDistrict|targetArea|locationIntent|keyword|cuisine|excludedCuisines|budgetPerPerson|budgetDirection|radiusKm|radiusDirection|nearby|arrivalTime|preferences|systemNotes|missingInformation|lockedConstraints|clearedFields|removedPreferences)");
        return Set.of("activeCriteria", "candidatePool", "latestBatchShopIds", "shownShopIds", "focusedShopId", "sourceDecisionSessionId", "activeDecisionSessionId", "dialogPhase", "activeTaskId", "taskCount", "batchCount").contains(path);
    }

    static boolean knownPath(String path) {
        String root = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
        if (!PATHS.contains(root)) return false;
        if ("criteria".equals(root)) return Set.of("criteria", "criteria.location", "criteria.cuisine", "criteria.budget", "criteria.distance", "criteria.preferences").contains(path) || path.matches("criteria\\.(location\\.(city|district|poi)|cuisine\\.(include|exclude)|budget\\.(hardMax|softTarget)|distance\\.hardMaxKm|preferences\\.(values|priorityOrder))");
        if ("semantic".equals(root)) return path.equals("semantic") || path.matches("semantic\\.(taskDirective|taskDirectiveEvidence|criteriaPatchCount|criteriaPatchDimensions|cleared|relativePreferences|feedbackKinds|feedback|requestKinds|referenceKinds|relationCount|conditionalChangeCount)");
        if ("searchAnchor".equals(root)) return path.equals("searchAnchor") || path.matches("searchAnchor\\.(source|city|district|poi|latitude|longitude|canonicalName)");
        return !path.contains(".");
    }
}
