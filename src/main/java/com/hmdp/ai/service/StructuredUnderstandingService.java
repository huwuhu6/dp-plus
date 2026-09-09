package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.CriteriaDeltaOperation;
import com.hmdp.ai.dto.DecisionContextSemanticQuery;
import com.hmdp.ai.dto.LocationExpression;
import com.hmdp.ai.dto.SemanticAct;
import com.hmdp.ai.dto.SemanticEvidence;
import com.hmdp.ai.dto.SemanticReference;
import com.hmdp.ai.dto.ShopFactSemanticQuery;
import com.hmdp.ai.dto.StructuredUnderstandingResult;
import com.hmdp.ai.dto.TurnSemanticIR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One-call structured linguistic interpretation. It is intentionally not a
 * business reducer: invalid output fails closed and the legacy pipeline remains
 * the authority.
 */
@Service
public class StructuredUnderstandingService {
    private static final Logger log = LoggerFactory.getLogger(StructuredUnderstandingService.class);

    @Resource private OpenAiCompatibleClient aiClient;
    @Resource private ObjectMapper objectMapper;
    @Resource private AiProperties aiProperties;

    public StructuredUnderstandingResult understand(String originalMessage,
                                                    List<Map<String, Object>> history,
                                                    Map<String, Object> readOnlyContext) {
        AiProperties.StructuredUnderstandingProperties properties = aiProperties == null
                ? null : aiProperties.getStructuredUnderstanding();
        if (properties == null || !properties.isEnabled()) return StructuredUnderstandingResult.disabled();
        long started = System.currentTimeMillis();
        try {
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(message("system", systemPrompt()));
            if (readOnlyContext != null && !readOnlyContext.isEmpty()) {
                messages.add(message("system", "只读上下文（不得输出其中的 shopId/sessionId）："
                        + objectMapper.writeValueAsString(readOnlyContext)));
            }
            if (history != null && !history.isEmpty()) {
                messages.add(message("system", "最近对话：" + compactHistory(history)));
            }
            messages.add(message("user", originalMessage == null ? "" : originalMessage));
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", "extract_turn_semantic_ir");
            function.put("description", "Extract only linguistic facts from one user turn.");
            function.put("parameters", schema());
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", function);
            Map<String, Object> toolChoice = new LinkedHashMap<>();
            toolChoice.put("type", "function");
            toolChoice.put("function", Collections.singletonMap("name", "extract_turn_semantic_ir"));
            JsonNode response = aiClient.chatCompletion(messages, Collections.singletonList(tool), toolChoice,
                    "STRUCTURED_UNDERSTANDING", properties.getTimeoutMs());
            String arguments = response.path("choices").path(0).path("message").path("tool_calls")
                    .path(0).path("function").path("arguments").asText("");
            if (arguments.trim().isEmpty()) throw new IllegalStateException("结构化语义模型未返回 tool arguments");
            JsonNode argumentNode = objectMapper.readTree(arguments);
            rejectForbiddenIdentityFields(argumentNode);
            TurnSemanticIR ir = objectMapper.readerFor(TurnSemanticIR.class)
                    .with(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(argumentNode.traverse(objectMapper));
            normalizeEmptyOptionals(ir);
            List<String> errors = validate(ir, originalMessage == null ? "" : originalMessage);
            StructuredUnderstandingResult result = new StructuredUnderstandingResult();
            result.setIr(ir);
            result.setValid(errors.isEmpty());
            result.setFallback(!errors.isEmpty());
            result.setFailureReason(errors.isEmpty() ? null : "INVALID_EVIDENCE_OR_SCHEMA");
            result.setValidationErrors(errors);
            result.setDurationMs(System.currentTimeMillis() - started);
            log.info("[AI][structured] event={} valid={} durationMs={} acts={} references={} deltas={} errors={}",
                    errors.isEmpty() ? "SUCCESS" : "INVALID", result.isValid(), result.getDurationMs(),
                    ir.getActs().size(), ir.getReferences().size(), ir.getCriteriaDelta().size(), errors.size());
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - started;
            log.warn("[AI][structured] event=FALLBACK durationMs={} errorType={} detail={}", duration,
                    e.getClass().getSimpleName(), compact(e.getMessage()));
            return StructuredUnderstandingResult.fallback(e.getClass().getSimpleName(), duration);
        }
    }

    private List<String> validate(TurnSemanticIR ir, String original) {
        List<String> errors = new ArrayList<>();
        if (ir == null) {
            errors.add("ir=null");
            return errors;
        }
        if (ir.getVersion() != null && !"v1".equalsIgnoreCase(ir.getVersion())) errors.add("version");
        if (ir.getActs() != null) for (int i = 0; i < ir.getActs().size(); i++) {
            SemanticAct item = ir.getActs().get(i);
            if (item == null || item.getType() == null) errors.add("acts[" + i + "].type");
            if (item == null || item.getEvidence() == null) errors.add("acts[" + i + "].evidence_missing");
            else validateEvidence(item.getEvidence(), original, "acts[" + i + "]", errors);
        }
        if (ir.getReferences() != null) for (int i = 0; i < ir.getReferences().size(); i++) {
            SemanticReference item = ir.getReferences().get(i);
            if (item == null || item.getScope() == null) errors.add("references[" + i + "].scope");
            validateSpan(item == null ? null : item.getSurface(), item == null ? null : item.getStart(),
                    item == null ? null : item.getEnd(), original, "references[" + i + "]", errors);
            if (item != null) validateEvidence(item.getEvidence(), original, "references[" + i + "].evidence", errors);
        }
        if (ir.getCriteriaDelta() != null) for (int i = 0; i < ir.getCriteriaDelta().size(); i++) {
            CriteriaDeltaOperation item = ir.getCriteriaDelta().get(i);
            if (item == null || item.getField() == null) errors.add("criteriaDelta[" + i + "].field");
            if (item == null || item.getOperation() == null) errors.add("criteriaDelta[" + i + "].operation");
            if (item == null || item.getEvidence() == null) errors.add("criteriaDelta[" + i + "].evidence_missing");
            else validateEvidence(item.getEvidence(), original, "criteriaDelta[" + i + "]", errors);
        }
        LocationExpression location = ir.getLocationExpression();
        if (location != null && location.getRawText() != null && !location.getRawText().isBlank()) {
            if (location.getEvidence() == null) errors.add("locationExpression.evidence_missing");
            else validateEvidence(location.getEvidence(), original, "locationExpression", errors);
        }
        if (ir.getShopFactQueries() != null) for (int i = 0; i < ir.getShopFactQueries().size(); i++) {
            ShopFactSemanticQuery item = ir.getShopFactQueries().get(i);
            if (item == null || item.getType() == null) errors.add("shopFactQueries[" + i + "].type");
            if (item == null || item.getEvidence() == null) errors.add("shopFactQueries[" + i + "].evidence_missing");
            else validateEvidence(item.getEvidence(), original, "shopFactQueries[" + i + "]", errors);
        }
        if (ir.getDecisionContextQuery() != null && ir.getDecisionContextQuery().getType() == null) {
            errors.add("decisionContextQuery.type");
        }
        return errors;
    }

    private void normalizeEmptyOptionals(TurnSemanticIR ir) {
        if (ir == null) return;
        LocationExpression location = ir.getLocationExpression();
        if (location != null && (location.getRawText() == null || location.getRawText().isBlank())
                && !Boolean.TRUE.equals(location.getReset())) ir.setLocationExpression(null);
        DecisionContextSemanticQuery query = ir.getDecisionContextQuery();
        if (query != null && query.getType() == null
                && (query.getReferenceId() == null || query.getReferenceId().isBlank())
                && (query.getConstraintKey() == null || query.getConstraintKey().isBlank())) {
            ir.setDecisionContextQuery(null);
        }
    }

    private void validateEvidence(SemanticEvidence evidence, String original, String path, List<String> errors) {
        if (evidence == null) return;
        validateSpan(evidence.getText(), evidence.getStart(), evidence.getEnd(), original, path, errors);
    }

    private void validateSpan(String text, Integer start, Integer end, String original, String path, List<String> errors) {
        if (text == null || start == null || end == null || start < 0 || start > end || end > original.length()
                || !original.substring(start, end).equals(text)) errors.add(path + ".evidence");
    }

    private void rejectForbiddenIdentityFields(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            if (node.has("shopId") || node.has("candidateId") || node.has("decisionSessionId"))
                throw new IllegalArgumentException("structured IR cannot contain entity identity");
            node.elements().forEachRemaining(this::rejectForbiddenIdentityFields);
        } else if (node.isArray()) node.elements().forEachRemaining(this::rejectForbiddenIdentityFields);
    }

    private String systemPrompt() {
        return "你是 Turn Semantic IR v1 抽取器。只抽取用户原话中的语言事实，不决定 Chat Action、Decision 状态、shopId、candidateId、sessionId 或最终行政身份。"
                + "未提及的字段保持空。evidence 的 text/start/end 必须逐字来自用户原话。允许一个 Turn 同时包含多个 acts，例如‘第一家太贵了，第二家有插座吗’同时输出 MUTATE_CRITERIA 和 ASK_SHOP_FACT。"
                + "LOCATION 只输出 locationExpression.rawText，不把地点写入 criteriaDelta；预算、距离、菜系、排除菜系、关键词、偏好和到店时间使用 criteriaDelta。"
                + "所有 business truth 由 Java resolver、merger、policy 和 tool 层决定。";
    }

    private Map<String, Object> schema() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("version", property("string", "v1"));
        p.put("acts", array("semantic act", actSchema()));
        p.put("references", array("linguistic references only", referenceSchema()));
        p.put("criteriaDelta", array("raw constraint operations", deltaSchema()));
        p.put("locationExpression", locationSchema());
        p.put("decisionContextQuery", querySchema());
        p.put("shopFactQueries", array("shop fact requests", factSchema()));
        p.put("ambiguities", array("unresolved linguistic ambiguity", property("string", "ambiguity")));
        root.put("properties", p);
        root.put("required", new ArrayList<>(p.keySet()));
        root.put("additionalProperties", false);
        return root;
    }

    private Map<String, Object> actSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", property("string", "REQUEST_RECOMMENDATION, MUTATE_CRITERIA, ASK_SHOP_FACT, ASK_DECISION_CONTEXT, EXPLORE_ALTERNATIVE, RESET_INTENT, CHITCHAT_OR_UNKNOWN"));
        p.put("evidence", evidenceSchema());
        p.put("details", array("details", property("string", "raw semantic detail")));
        return object(p, p.keySet());
    }

    private Map<String, Object> referenceSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", property("string", "turn-local reference id"));
        p.put("scope", property("string", "LATEST, EARLIEST, FOCUSED, UNSPECIFIED"));
        p.put("ordinal", property("integer", "ordinal, null when not ordinal"));
        p.put("surface", property("string", "exact surface"));
        p.put("start", property("integer", "inclusive start"));
        p.put("end", property("integer", "exclusive end"));
        p.put("qualifier", property("string", "optional qualifier"));
        p.put("deictic", property("boolean", "deictic reference"));
        p.put("evidence", evidenceSchema());
        return object(p, p.keySet());
    }

    private Map<String, Object> deltaSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("field", property("string", "BUDGET_PER_PERSON, RADIUS_KM, NEARBY, CUISINE, EXCLUDED_CUISINE, KEYWORD, PREFERENCE, ARRIVAL_TIME"));
        p.put("operation", property("string", "SET, INCREASE, DECREASE, ADD, REMOVE, CLEAR"));
        p.put("rawValue", property("string", "raw semantic value"));
        p.put("anchorReferenceId", property("string", "optional turn-local reference id"));
        p.put("evidence", evidenceSchema());
        return object(p, p.keySet());
    }

    private Map<String, Object> locationSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("rawText", property("string", "raw location mention"));
        p.put("reset", property("boolean", "whether location is reset"));
        p.put("evidence", evidenceSchema());
        return object(p, p.keySet());
    }

    private Map<String, Object> querySchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", property("string", "WHY_RECOMMENDED, CONSTRAINT_PROVENANCE, CURRENT_CRITERIA, EXECUTED_SEARCH_SCOPE"));
        p.put("referenceId", property("string", "optional reference id"));
        p.put("constraintKey", property("string", "canonical query key only"));
        return object(p, p.keySet());
    }

    private Map<String, Object> factSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", property("string", "STATIC_DETAIL, EVIDENCE, VOUCHER"));
        p.put("referenceId", property("string", "reference id"));
        p.put("evidence", evidenceSchema());
        return object(p, p.keySet());
    }

    private Map<String, Object> evidenceSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("text", property("string", "verbatim source text"));
        p.put("start", property("integer", "inclusive start"));
        p.put("end", property("integer", "exclusive end"));
        return object(p, p.keySet());
    }

    private Map<String, Object> object(Map<String, Object> properties, java.util.Set<String> required) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", "object"); value.put("properties", properties);
        value.put("required", new ArrayList<>(required)); value.put("additionalProperties", false);
        return value;
    }

    private Map<String, Object> array(String description, Map<String, Object> items) {
        Map<String, Object> value = new LinkedHashMap<>(); value.put("type", "array"); value.put("description", description); value.put("items", items); return value;
    }

    private Map<String, Object> property(String type, String description) {
        Map<String, Object> value = new LinkedHashMap<>(); value.put("type", type); value.put("description", description); return value;
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> value = new LinkedHashMap<>(); value.put("role", role); value.put("content", content); return value;
    }

    private String compactHistory(List<Map<String, Object>> history) {
        StringBuilder value = new StringBuilder();
        int start = Math.max(0, history.size() - 6);
        for (int i = start; i < history.size(); i++) {
            if (value.length() > 0) value.append(" | ");
            Map<String, Object> item = history.get(i);
            value.append(item.get("role")).append(":").append(item.get("content"));
        }
        return value.toString();
    }

    private String compact(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ");
        return normalized.length() > 500 ? normalized.substring(0, 500) : normalized;
    }
}
