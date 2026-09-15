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
    private V2DatasetValidator() { }

    public static void validate(String version, List<AiConversationEvaluationCase> cases, ObjectMapper mapper) {
        if (!version.endsWith("-v2")) return;
        Set<String> codes = new HashSet<>();
        for (AiConversationEvaluationCase item : cases) {
            if (!Boolean.TRUE.equals(item.getActive()) || item.getCaseCode() == null || !codes.add(item.getCaseCode()))
                throw new IllegalArgumentException("V2 dataset requires unique active caseCode");
            try {
                List<Map<String, Object>> turns = mapper.readValue(item.getTurnsJson(), new TypeReference<>() { });
                if (turns.isEmpty()) throw new IllegalArgumentException("V2 dataset requires non-empty turns: " + item.getCaseCode());
                if (item.getExpectedFinalStatus() != null && item.getExpectedFinalStatus().contains("|"))
                    throw new IllegalArgumentException("V2 dataset forbids ambiguous final status: " + item.getCaseCode());
                if (item.getExpectedV2OutcomesJson() != null) for (Map<String, Object> outcome : mapper.readValue(item.getExpectedV2OutcomesJson(), new TypeReference<List<Map<String, Object>>>() { })) {
                    Number turn = (Number) outcome.get("turn");
                    if (turn == null || turn.intValue() < 1 || turn.intValue() > turns.size()) throw new IllegalArgumentException("invalid expected turn");
                    Object raw = outcome.get("assertions");
                    if (raw instanceof Map<?, ?> assertions) for (Object value : assertions.values()) if (value instanceof Map<?, ?> expression) {
                        long count = expression.keySet().stream().map(String::valueOf).filter(OPERATORS::contains).count();
                        if (count != 1) throw new IllegalArgumentException("invalid assertion operator");
                    }
                }
            } catch (Exception e) { throw new IllegalArgumentException("invalid V2 dataset case: " + item.getCaseCode(), e); }
        }
    }
}
