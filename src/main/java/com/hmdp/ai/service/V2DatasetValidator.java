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
        if (!version.endsWith("-v2")) return;
        Set<String> codes = new HashSet<>();
        for (AiConversationEvaluationCase item : cases) {
            if (!Boolean.TRUE.equals(item.getActive()) || item.getCaseCode() == null || !codes.add(item.getCaseCode()))
                throw new IllegalArgumentException("V2 dataset requires unique active caseCode");
            if (!version.equals(item.getDatasetVersion())) throw new IllegalArgumentException("datasetVersion mismatch: " + item.getCaseCode());
            try {
                List<Map<String, Object>> turns = mapper.readValue(item.getTurnsJson(), new TypeReference<>() { });
                if (turns.isEmpty()) throw new IllegalArgumentException("V2 dataset requires non-empty turns: " + item.getCaseCode());
                if (item.getExpectedFinalStatus() != null && item.getExpectedFinalStatus().contains("|"))
                    throw new IllegalArgumentException("V2 dataset forbids ambiguous final status: " + item.getCaseCode());
                if (item.getExpectedV2OutcomesJson() != null) for (Map<String, Object> outcome : mapper.readValue(item.getExpectedV2OutcomesJson(), new TypeReference<List<Map<String, Object>>>() { })) {
                    Number turn = (Number) outcome.get("turn");
                    if (turn == null || turn.intValue() < 1 || turn.intValue() > turns.size()) throw new IllegalArgumentException("invalid expected turn");
                    Object raw = outcome.get("assertions");
                    if (raw instanceof Map<?, ?> assertions) for (Map.Entry<?, ?> assertion : assertions.entrySet()) {
                        if (!knownPath(String.valueOf(assertion.getKey()))) throw new IllegalArgumentException("unknown assertion path");
                        if (assertion.getValue() instanceof Map<?, ?> expression) {
                            if (expression.size() != 1 || !OPERATORS.contains(String.valueOf(expression.keySet().iterator().next())))
                                throw new IllegalArgumentException("invalid assertion operator");
                        }
                    }
                }
                if (item.getExpectedRelationsJson() != null) for (Map<String, Object> relation : mapper.readValue(item.getExpectedRelationsJson(), new TypeReference<List<Map<String, Object>>>() { })) {
                    Number from = (Number) relation.get("fromTurn"); Number to = (Number) relation.get("toTurn");
                    if (from == null || to == null || from.intValue() < 1 || to.intValue() < 1 || from.intValue() >= to.intValue() || to.intValue() > turns.size())
                        throw new IllegalArgumentException("invalid cross-turn relation");
                    String type = String.valueOf(relation.get("type"));
                    String expected = String.valueOf(relation.get("relation"));
                    if (!RELATIONS.getOrDefault(type, Set.of()).contains(expected))
                        throw new IllegalArgumentException("unknown cross-turn relation");
                    if ("groundedOrdinal".equals(relation.get("type"))) {
                        Number ordinal = (Number) relation.get("ordinal");
                        if (!"EQUALS_VISIBLE".equals(relation.get("relation")) || ordinal == null || ordinal.intValue() < 1)
                            throw new IllegalArgumentException("invalid grounded ordinal relation");
                    }
                }
            } catch (Exception e) { throw new IllegalArgumentException("invalid V2 dataset case: " + item.getCaseCode(), e); }
        }
    }

    private static boolean knownPath(String path) {
        String root = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
        if (!PATHS.contains(root)) return false;
        if ("criteria".equals(root)) return path.equals("criteria") || path.matches("criteria\\.(location\\.(city|district|poi)|cuisine\\.(include|exclude)|budget\\.(hardMax|softTarget)|distance\\.hardMaxKm|preferences\\.(values|priorityOrder))");
        if ("semantic".equals(root)) return path.equals("semantic") || path.matches("semantic\\.(taskDirective|taskDirectiveEvidence|criteriaPatchCount|criteriaPatchDimensions|cleared|relativePreferences|feedbackKinds|feedback|requestKinds|referenceKinds|relationCount|conditionalChangeCount)");
        if ("searchAnchor".equals(root)) return path.equals("searchAnchor") || path.matches("searchAnchor\\.(source|city|district|poi|latitude|longitude|canonicalName)");
        return !path.contains(".");
    }
}
