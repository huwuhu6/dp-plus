package com.hmdp.ai.v2.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.v2.semantic.DiningCriteria;
import com.hmdp.ai.v2.semantic.DiningCriteriaPatch;
import com.hmdp.ai.v2.semantic.EntityFeedback;
import com.hmdp.ai.v2.semantic.EntityReference;
import com.hmdp.ai.v2.semantic.RequirementChange;
import com.hmdp.ai.v2.semantic.TaskDirective;
import com.hmdp.ai.v2.semantic.TaskSelector;
import com.hmdp.ai.v2.semantic.TurnSemantics;
import com.hmdp.ai.v2.semantic.UserRequest;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The model is only allowed to describe user meaning.  It cannot name durable
 * identities, produce a plan, or request a mutation.  Those are all produced
 * by deterministic grounding and reducers after this boundary.
 */
@Service
public class SemanticInterpreter {
    private static final String FUNCTION = "submit_turn_semantics";
    @Resource private OpenAiCompatibleClient aiClient;
    @Resource private ObjectMapper objectMapper;

    public TurnSemantics interpret(String userTurn) {
        if (userTurn == null || userTurn.isBlank()) throw new IllegalArgumentException("message cannot be blank");
        try {
            return parse(call(userTurn, false));
        } catch (RuntimeException firstFailure) {
            try {
                return parse(call(userTurn, true));
            } catch (RuntimeException ignored) {
                throw new SemanticInterpretationException("无法可靠理解这句话，请换一种说法或说明要修改的条件", firstFailure);
            }
        }
    }

    /** Deterministic schema boundary, also used by contract tests without a model. */
    public TurnSemantics parse(JsonNode root) {
        if (root == null || !root.isObject()) throw new IllegalArgumentException("semantic output must be an object");
        TaskDirective directive = enumValue(TaskDirective.class, root.path("taskDirective").asText("CONTINUE"));
        List<RequirementChange> changes = new ArrayList<>();
        JsonNode criteria = root.path("criteria");
        Set<RequirementChange.ClearedCriterion> cleared = enumSet(RequirementChange.ClearedCriterion.class, root.path("cleared"));
        if (!criteria.isMissingNode() || !cleared.isEmpty()) changes.add(new RequirementChange.CriteriaPatch(criteriaPatch(criteria), cleared));
        for (JsonNode item : root.path("relativePreferences")) {
            changes.add(new RequirementChange.RelativePreference(
                    enumValue(DiningCriteria.PreferenceDimension.class, requiredText(item, "dimension")),
                    enumValue(RequirementChange.Direction.class, requiredText(item, "direction"))));
        }
        for (JsonNode item : root.path("relaxationAuthorizations")) {
            changes.add(new RequirementChange.RelaxationAuthorization(enumValue(DiningCriteria.PreferenceDimension.class, item.asText())));
        }
        for (JsonNode item : root.path("requirementLocks")) {
            changes.add(new RequirementChange.RequirementLock(enumValue(DiningCriteria.PreferenceDimension.class, item.asText())));
        }
        return new TurnSemantics(directive, changes, feedback(root.path("feedback")),
                requests(root.path("requests")), List.of(), references(root.path("references")));
    }

    private JsonNode call(String userTurn, boolean repair) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", FUNCTION);
        function.put("description", "Return canonical dining user semantics. Never return ids, plans, tools, SQL, or mutations.");
        function.put("parameters", schema());
        Map<String, Object> tool = Map.of("type", "function", "function", function);
        Map<String, Object> choice = Map.of("type", "function", "function", Map.of("name", FUNCTION));
        String system = "You are a semantic parser for a dining assistant. Output only the function call. "
                + "References may only be ordinal, focused, named, or task selector; never invent database ids. "
                + "Use null/omission for untouched criteria and cleared for explicit removal. "
                + (repair ? "Your previous output was invalid. Obey the schema exactly." : "");
        JsonNode response = aiClient.chatCompletion(List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", userTurn)), List.of(tool), choice, "V2_SEMANTIC_INTERPRETATION");
        String arguments = response.path("choices").path(0).path("message").path("tool_calls")
                .path(0).path("function").path("arguments").asText();
        if (arguments.isBlank()) throw new IllegalArgumentException("model returned no semantics");
        try {
            return objectMapper.readTree(arguments);
        } catch (Exception e) {
            throw new IllegalArgumentException("model returned invalid semantic json", e);
        }
    }

    private DiningCriteriaPatch criteriaPatch(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return new DiningCriteriaPatch(null, null, null, null, null, null);
        DiningCriteria.LocationCriteria location = any(node, "city", "district", "poi")
                ? new DiningCriteria.LocationCriteria(text(node, "city"), text(node, "district"), text(node, "poi")) : null;
        DiningCriteria.CuisineCriteria cuisine = any(node, "cuisine", "excludedCuisines")
                ? new DiningCriteria.CuisineCriteria(text(node, "cuisine"), strings(node.path("excludedCuisines"))) : null;
        DiningCriteria.BudgetCriteria budget = any(node, "budgetSoft", "budgetHard")
                ? new DiningCriteria.BudgetCriteria(decimal(node, "budgetSoft"), decimal(node, "budgetHard")) : null;
        DiningCriteria.DistanceCriteria distance = node.hasNonNull("distanceKm")
                ? new DiningCriteria.DistanceCriteria(decimal(node, "distanceKm")) : null;
        return new DiningCriteriaPatch(location, cuisine, budget, distance, null, null);
    }

    private List<EntityFeedback> feedback(JsonNode items) {
        List<EntityFeedback> result = new ArrayList<>();
        for (JsonNode item : items) {
            if (item.path("batch").asBoolean(false)) {
                result.add(new EntityFeedback.BatchFeedback(new com.hmdp.ai.v2.semantic.BatchRef(
                        com.hmdp.ai.v2.semantic.BatchRef.BatchSelector.CURRENT_VISIBLE),
                        enumValue(EntityFeedback.BatchPolarity.class, item.path("polarity").asText("NEGATIVE")),
                        enumValue(EntityFeedback.FeedbackAspect.class, item.path("aspect").asText("UNSPECIFIED"))));
            } else result.add(new EntityFeedback.EntityFeedbackItem(reference(item.path("target")),
                    enumValue(EntityFeedback.FeedbackKind.class, requiredText(item, "kind")),
                    enumValue(EntityFeedback.FeedbackAspect.class, item.path("aspect").asText("UNSPECIFIED"))));
        }
        return result;
    }

    private List<UserRequest> requests(JsonNode items) {
        List<UserRequest> result = new ArrayList<>();
        for (JsonNode item : items) {
            String id = requiredText(item, "requestId");
            switch (requiredText(item, "type")) {
                case "RECOMMENDATION" -> result.add(new UserRequest.RecommendationRequest(id));
                case "ALTERNATIVES" -> result.add(new UserRequest.AlternativesRequest(id, item.path("count").asInt(2)));
                case "FACT" -> result.add(new UserRequest.FactQueryRequest(id, reference(item.path("target")),
                        enumValue(UserRequest.FactType.class, requiredText(item, "fact"))));
                case "SELECT" -> result.add(new UserRequest.SelectRequest(id, reference(item.path("target"))));
                case "SIMILAR" -> result.add(new UserRequest.SimilarRequest(id, reference(item.path("target"))));
                case "EXPLORE" -> result.add(new UserRequest.ExploreRequest(id, reference(item.path("target")), item.path("unboundedContinuation").asBoolean()));
                case "GENERAL" -> result.add(new UserRequest.GeneralKnowledgeRequest(id, requiredText(item, "topic")));
                default -> throw new IllegalArgumentException("unsupported request type");
            }
        }
        return result;
    }

    private List<EntityReference> references(JsonNode items) {
        List<EntityReference> result = new ArrayList<>();
        for (JsonNode item : items) result.add(reference(item));
        return result;
    }

    private EntityReference reference(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) throw new IllegalArgumentException("request target is required");
        if (node.has("ordinal")) return new EntityReference.OrdinalRef(node.path("ordinal").asInt());
        if (node.path("focused").asBoolean(false)) return new EntityReference.FocusedEntityRef();
        if (node.hasNonNull("name")) return new EntityReference.NamedEntityRef(node.path("name").asText());
        String selector = node.path("taskSelector").asText();
        return switch (selector) {
            case "EARLIEST" -> new EntityReference.TaskRef(new TaskSelector.Earliest());
            case "ACTIVE" -> new EntityReference.TaskRef(new TaskSelector.Active());
            case "MATCH_CONTEXT" -> new EntityReference.TaskRef(new TaskSelector.MatchContext(text(node, "goalCategory"), text(node, "city")));
            default -> throw new IllegalArgumentException("unsupported reference");
        };
    }

    private Map<String, Object> schema() {
        Map<String, Object> string = Map.of("type", "string");
        Map<String, Object> number = Map.of("type", "number");
        Map<String, Object> target = Map.of("type", "object", "properties", Map.of(
                "ordinal", Map.of("type", "integer"), "focused", Map.of("type", "boolean"),
                "name", string, "taskSelector", string, "goalCategory", string, "city", string));
        Map<String, Object> request = Map.of("type", "object", "properties", Map.of(
                "requestId", string, "type", string, "target", target, "count", Map.of("type", "integer"),
                "fact", string, "topic", string, "unboundedContinuation", Map.of("type", "boolean")));
        Map<String, Object> criteria = Map.of("type", "object", "properties", Map.of(
                "city", string, "district", string, "poi", string, "cuisine", string,
                "excludedCuisines", Map.of("type", "array", "items", string),
                "budgetSoft", number, "budgetHard", number, "distanceKm", number));
        return Map.of("type", "object", "properties", Map.of(
                "taskDirective", string, "criteria", criteria,
                "cleared", Map.of("type", "array", "items", string),
                "relativePreferences", Map.of("type", "array", "items", Map.of("type", "object")),
                "relaxationAuthorizations", Map.of("type", "array", "items", string),
                "requirementLocks", Map.of("type", "array", "items", string),
                "feedback", Map.of("type", "array", "items", Map.of("type", "object")),
                "requests", Map.of("type", "array", "items", request),
                "references", Map.of("type", "array", "items", target)));
    }

    private <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        try { return Enum.valueOf(type, value); }
        catch (Exception e) { throw new IllegalArgumentException("invalid " + type.getSimpleName() + ": " + value, e); }
    }
    private <T extends Enum<T>> Set<T> enumSet(Class<T> type, JsonNode values) {
        java.util.EnumSet<T> result = java.util.EnumSet.noneOf(type);
        for (JsonNode value : values) result.add(enumValue(type, value.asText()));
        return result;
    }
    private String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + field);
        return value;
    }
    private String text(JsonNode node, String field) { return node.hasNonNull(field) ? node.path(field).asText() : null; }
    private boolean any(JsonNode node, String... fields) { for (String field : fields) if (node.hasNonNull(field)) return true; return false; }
    private List<String> strings(JsonNode node) { List<String> result = new ArrayList<>(); for (JsonNode item : node) result.add(item.asText()); return result; }
    private BigDecimal decimal(JsonNode node, String field) { return node.hasNonNull(field) ? node.path(field).decimalValue() : null; }
}
