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
import com.hmdp.ai.v2.semantic.SemanticRelation;
import com.hmdp.ai.v2.semantic.ObservationPredicate;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final ThreadLocal<List<Map<String, Object>>> evaluationAttempts = ThreadLocal.withInitial(ArrayList::new);

    /** Evaluation-only request trace; it is never reducer input or durable domain state. */
    public List<Map<String, Object>> takeEvaluationAttempts() {
        List<Map<String, Object>> attempts = List.copyOf(evaluationAttempts.get());
        evaluationAttempts.remove();
        return attempts;
    }

    public TurnSemantics interpret(String userTurn) {
        if (userTurn == null || userTurn.isBlank()) throw new IllegalArgumentException("message cannot be blank");
        evaluationAttempts.remove();
        try {
            return parseAndValidate(userTurn);
        } catch (RuntimeException firstFailure) {
            if (isTransportFailure(firstFailure)) throw new SemanticModelAvailabilityException("语义模型暂不可用，请稍后重试", firstFailure);
            Violation violation = violationOf(firstFailure);
            try {
                return parseAndValidate(userTurn, violation);
            } catch (RuntimeException secondFailure) {
                SemanticInterpretationException error = new SemanticInterpretationException("无法可靠理解这句话，请换一种说法或说明要修改的条件", secondFailure);
                error.addSuppressed(firstFailure);
                throw error;
            }
        }
    }

    private TurnSemantics parseAndValidate(String userTurn) { return parseAndValidate(userTurn, null); }
    private TurnSemantics parseAndValidate(String userTurn, Violation repair) {
        CallResult call;
        try { call = call(userTurn, repair); }
        catch (RuntimeException failure) {
            recordEvaluationAttempt(repair, null, "MODEL_TRANSPORT_FAILURE");
            throw failure;
        }
        try {
        TurnSemantics semantics = normalizeLocationProvenance(userTurn, normalizeDeterministicBudget(userTurn, parse(call.root())));
        if (requiresHardBudget(userTurn) && !hasHardBudget(semantics)) throw violation(Violation.HARD_BUDGET_REQUIRED);
        if (requiresBudgetClear(userTurn) && !hasBudgetClearWithoutReplacement(semantics)) throw violation(Violation.BUDGET_CLEAR_REQUIRED);
        if (requiresConditionalFallback(userTurn) && semantics.requirementChanges().stream()
                .filter(RequirementChange.ConditionalRequirementChange.class::isInstance)
                .map(RequirementChange.ConditionalRequirementChange.class::cast)
                .noneMatch(change -> !isEmpty(change.change()))) {
            // A parsed-but-empty fallback silently turns a user promise into an unconstrained search.
            // Treat it as invalid so the existing repair call can obtain a complete semantic contract.
            throw violation(Violation.CONDITIONAL_FALLBACK_REQUIRED);
        }
        recordEvaluationAttempt(repair, call.arguments(), "VALID");
        return semantics;
        } catch (RuntimeException failure) {
            recordEvaluationAttempt(repair, call.arguments(), violationOf(failure).name());
            throw failure;
        }
    }

    private boolean requiresConditionalFallback(String userTurn) {
        return userTurn.matches("(?s).*如果.*(?:没有|没|无).*(?:就|则|改|换).*?");
    }
    private boolean requiresHardBudget(String userTurn) {
        return userTurn.matches("(?s).*(?:人均|预算|消费)[^\\d]{0,12}\\d+(?:\\.\\d+)?(?:元)?(?:以内|以下).*?")
                || userTurn.matches("(?s).*(?:人均|预算|消费)[^\\d]{0,12}(?:不超过|最多|上限)[^\\d]{0,12}\\d+(?:\\.\\d+)?(?:元)?.*")
                || userTurn.matches("(?s).*预算[^\\d]{0,12}(?:改成|改为|调整为|设为|提高到|降到)[^\\d]{0,12}\\d+(?:\\.\\d+)?(?:元)?.*");
    }
    /** Model-extracted place text is admitted only when it is explicitly present in this user turn. */
    private TurnSemantics normalizeLocationProvenance(String userTurn, TurnSemantics semantics) {
        List<RequirementChange> changes = new ArrayList<>();
        for (RequirementChange change : semantics.requirementChanges()) {
            if (!(change instanceof RequirementChange.CriteriaPatch criteria) || criteria.patch().location() == null) { changes.add(change); continue; }
            DiningCriteriaPatch patch = criteria.patch(); DiningCriteria.LocationCriteria location = patch.location();
            String city = mentionedLocation(userTurn, location.city(), true) ? location.city() : null;
            String district = mentionedLocation(userTurn, location.district(), true) ? location.district() : null;
            String poi = mentionedLocation(userTurn, location.poi(), false) ? location.poi() : null;
            DiningCriteria.LocationCriteria trusted = city == null && district == null && poi == null ? null : new DiningCriteria.LocationCriteria(city, district, poi);
            changes.add(new RequirementChange.CriteriaPatch(new DiningCriteriaPatch(trusted, patch.cuisine(), patch.budget(), patch.distance(), patch.diningTime(), patch.semanticPreferences()), criteria.cleared()));
        }
        return new TurnSemantics(semantics.taskDirective(), semantics.taskDirectiveEvidence(), changes, semantics.entityFeedback(), semantics.requests(), semantics.relations(), semantics.references());
    }
    private boolean mentionedLocation(String userTurn, String value, boolean administrative) {
        if (value == null || value.isBlank()) return false;
        if (userTurn.contains(value)) return true;
        if (!administrative) return false;
        String normalized = value.replaceAll("(?:市|区|县|自治州|自治区)$", "");
        return !normalized.isBlank() && userTurn.contains(normalized);
    }
    /** High-confidence numeric budget forms are normalized before reduction; ambiguous price language remains model-owned. */
    private TurnSemantics normalizeDeterministicBudget(String userTurn, TurnSemantics semantics) {
        if (!requiresHardBudget(userTurn) && !requiresBudgetClear(userTurn)) return semantics;
        List<RequirementChange> changes = new ArrayList<>();
        for (RequirementChange change : semantics.requirementChanges()) {
            if (change instanceof RequirementChange.CriteriaPatch criteria && requiresBudgetClear(userTurn)) {
                DiningCriteriaPatch patch = criteria.patch();
                changes.add(new RequirementChange.CriteriaPatch(new DiningCriteriaPatch(patch.location(), patch.cuisine(), null,
                        patch.distance(), patch.diningTime(), patch.semanticPreferences()), unionBudgetClear(criteria.cleared())));
            } else changes.add(change);
        }
        if (requiresBudgetClear(userTurn)) {
            if (changes.stream().noneMatch(RequirementChange.CriteriaPatch.class::isInstance))
                changes.add(new RequirementChange.CriteriaPatch(new DiningCriteriaPatch(null, null, null, null, null, null), Set.of(RequirementChange.ClearedCriterion.BUDGET)));
        } else if (!hasHardBudget(semantics)) {
            changes.add(new RequirementChange.CriteriaPatch(new DiningCriteriaPatch(null, null,
                    new DiningCriteria.BudgetCriteria(null, hardBudgetValue(userTurn)), null, null, null), Set.of()));
        }
        return new TurnSemantics(semantics.taskDirective(), semantics.taskDirectiveEvidence(), changes, semantics.entityFeedback(), semantics.requests(), semantics.relations(), semantics.references());
    }
    private Set<RequirementChange.ClearedCriterion> unionBudgetClear(Set<RequirementChange.ClearedCriterion> cleared) {
        java.util.EnumSet<RequirementChange.ClearedCriterion> result = java.util.EnumSet.noneOf(RequirementChange.ClearedCriterion.class);
        result.addAll(cleared); result.add(RequirementChange.ClearedCriterion.BUDGET); return result;
    }
    private BigDecimal hardBudgetValue(String userTurn) {
        Pattern pattern = Pattern.compile("(?:人均|预算|消费)[^\\d]{0,12}(?:不超过|最多|上限|改成|改为|调整为|设为|提高到|降到)?[^\\d]{0,12}(\\d+(?:\\.\\d+)?)");
        Matcher matcher = pattern.matcher(userTurn);
        if (!matcher.find()) throw new IllegalStateException("hard budget detector and extractor disagree");
        return new BigDecimal(matcher.group(1));
    }
    private boolean requiresBudgetClear(String userTurn) {
        return userTurn.matches("(?s).*(?:预算不限|不限预算|不要预算限制|取消预算限制|去掉预算条件).*");
    }
    private boolean hasHardBudget(TurnSemantics semantics) { return semantics.requirementChanges().stream()
            .filter(RequirementChange.CriteriaPatch.class::isInstance).map(RequirementChange.CriteriaPatch.class::cast)
            .anyMatch(change -> change.patch().budget() != null && change.patch().budget().hardMax() != null); }
    private boolean hasBudgetClearWithoutReplacement(TurnSemantics semantics) { return semantics.requirementChanges().stream()
            .filter(RequirementChange.CriteriaPatch.class::isInstance).map(RequirementChange.CriteriaPatch.class::cast)
            .anyMatch(change -> change.cleared().contains(RequirementChange.ClearedCriterion.BUDGET)
                    && (change.patch().budget() == null || (change.patch().budget().hardMax() == null && change.patch().budget().softTarget() == null))); }
    private SemanticContractViolation violation(Violation code) { return new SemanticContractViolation(code); }
    private Violation violationOf(RuntimeException failure) { return failure instanceof SemanticContractViolation v ? v.code() : Violation.GENERIC_CONTRACT; }
    private boolean isTransportFailure(Throwable failure) { for (Throwable current = failure; current != null; current = current.getCause()) if (current instanceof RestClientException || current instanceof java.net.SocketTimeoutException || current instanceof java.net.ConnectException) return true; return false; }

    private boolean isEmpty(RequirementChange.CriteriaPatch change) {
        DiningCriteriaPatch patch = change.patch();
        return patch.location() == null && patch.cuisine() == null && patch.budget() == null
                && patch.distance() == null && patch.diningTime() == null && patch.semanticPreferences() == null
                && change.cleared().isEmpty();
    }

    /** Deterministic schema boundary, also used by contract tests without a model. */
    public TurnSemantics parse(JsonNode root) {
        if (root == null || !root.isObject()) throw new IllegalArgumentException("semantic output must be an object");
        TaskDirective directive = enumValue(TaskDirective.class, root.path("taskDirective").asText("CONTINUE"));
        var directiveEvidence = enumValue(com.hmdp.ai.v2.semantic.TaskDirectiveEvidence.class,
                root.path("taskDirectiveEvidence").asText("NONE"));
        validateDirectiveEvidence(directive, directiveEvidence);
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
        for (JsonNode item : root.path("conditionalRequirementChanges")) {
            changes.add(new RequirementChange.ConditionalRequirementChange(requiredText(item, "observedRequestId"),
                    predicate(item.path("predicate")), new RequirementChange.CriteriaPatch(criteriaPatch(item.path("criteria")),
                    enumSet(RequirementChange.ClearedCriterion.class, item.path("cleared")))));
        }
        List<SemanticRelation> relations = new ArrayList<>();
        for (JsonNode item : root.path("relations")) relations.add(new SemanticRelation(requiredText(item, "observedRequestId"),
                predicate(item.path("predicate")), requiredText(item, "dependentRequestId")));
        return new TurnSemantics(directive, directiveEvidence, changes, feedback(root.path("feedback")),
                requests(root.path("requests")), relations, references(root.path("references")));
    }

    /** A task operation must be an explicit user semantic, never an inference from a search request. */
    private void validateDirectiveEvidence(TaskDirective directive, com.hmdp.ai.v2.semantic.TaskDirectiveEvidence evidence) {
        var expected = switch (directive) {
            case CONTINUE -> com.hmdp.ai.v2.semantic.TaskDirectiveEvidence.NONE;
            case START_NEW -> com.hmdp.ai.v2.semantic.TaskDirectiveEvidence.EXPLICIT_NEW_TASK;
            case RESTORE -> com.hmdp.ai.v2.semantic.TaskDirectiveEvidence.EXPLICIT_RESTORE;
            case ABANDON -> com.hmdp.ai.v2.semantic.TaskDirectiveEvidence.EXPLICIT_ABANDON;
        };
        if (expected != evidence) throw new IllegalArgumentException("taskDirective lacks matching explicit evidence");
    }

    private CallResult call(String userTurn, Violation repair) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", FUNCTION);
        function.put("description", "Return canonical dining user semantics. Never return ids, plans, tools, SQL, or mutations.");
        function.put("parameters", schema());
        Map<String, Object> tool = Map.of("type", "function", "function", function);
        Map<String, Object> choice = Map.of("type", "function", "function", Map.of("name", FUNCTION));
        String system = "You are a semantic parser for a dining assistant. Output only the function call. "
                + "Use exact uppercase enum values from the schema; do not replace them with natural-language labels such as find or fact. "
                + "taskDirectiveEvidence is NONE for CONTINUE. START_NEW is permitted only for an explicitly independent new decision task and then taskDirectiveEvidence must be EXPLICIT_NEW_TASK; RESTORE/ABANDON likewise require EXPLICIT_RESTORE/EXPLICIT_ABANDON. A constraint edit, clear, feedback, ordinal follow-up, alternatives request, similar request, or conditional fallback is CONTINUE with NONE, never START_NEW. "
                + "A dining recommendation request must use type RECOMMENDATION; a general-information question must use type GENERAL with its topic, never FACT. "
                + "Every request needs requestId, type, and topic; topic must be non-empty for GENERAL and empty for other request types. "
                + "For RESTORE, emit a task selector only when the user explicitly specifies earliest, active, or a uniquely identifying context. If the target task is not uniquely specified, emit RESTORE with empty references so runtime asks for clarification; never default to EARLIEST. "
                + "Every request must include target and fact: use a valid reference for FACT, SELECT, SIMILAR, and COMPARE; use {} and fact DETAIL for other types. For EXPLORE with unboundedContinuation=true, target may be empty because research is not scoped to a visible entity. For bounded/entity-scoped EXPLORE, provide a valid target. A reference with lastVisible:true means the final candidate in the current visible batch; never convert it to a guessed ordinal. "
                + "For FACT, fact must describe the requested fact using its enum. "
                + "Every feedback item must include target, kind, aspect, batch, and polarity. For a single entity use its reference, batch false, and polarity NEGATIVE as an ignored placeholder; for a batch use target {}, kind REJECT as an ignored placeholder, batch true, and the requested polarity. "
                + "Examples: ‘找附近的餐厅’ -> CONTINUE/NONE plus requests [{requestId: r1, type: RECOMMENDATION, topic: '', target: {}, fact: DETAIL}]; runtime creates a task only when no active task exists. ‘新开一个任务找日料’ -> START_NEW/EXPLICIT_NEW_TASK. "
                + "‘第一家几点营业？’ -> CONTINUE/NONE plus requests [{requestId: r1, type: FACT, topic: '', target: {ordinal: 1}, fact: DETAIL}]; "
                + "‘第一家我不想要，排除掉’ -> CONTINUE plus feedback [{target: {ordinal: 1}, kind: REJECT, aspect: UNSPECIFIED, batch: false, polarity: NEGATIVE}]; "
                + "‘预算不限’ -> CONTINUE plus cleared [BUDGET] and requests [{requestId: r1, type: RECOMMENDATION, topic: '', target: {}, fact: DETAIL}]; "
                + "‘第一家太贵了’ -> CONTINUE plus feedback [{target: {ordinal: 1}, kind: CRITIQUE, aspect: PRICE, batch: false, polarity: NEGATIVE}] and requests []; "
                + "‘第二家不错’ -> CONTINUE plus feedback [{target: {ordinal: 2}, kind: POSITIVE, aspect: UNSPECIFIED, batch: false, polarity: NEGATIVE}] and requests []; "
                + "‘这几家都不喜欢’ -> CONTINUE plus feedback [{target: {}, kind: REJECT, aspect: UNSPECIFIED, batch: true, polarity: NEGATIVE}] and requests []; "
                + "‘换一批’ -> CONTINUE plus requests [{requestId: r1, type: ALTERNATIVES, topic: '', target: {}, fact: DETAIL}]; "
                + "A request to compare visible candidates must use COMPARE with targets and dimensions. A request to keep researching or investigate in depth without a bounded next step must use EXPLORE with unboundedContinuation true. "
                + "‘恢复最早的推荐任务’ -> RESTORE/EXPLICIT_RESTORE plus references [{taskSelector: EARLIEST}] and requests []; "
                + "‘再便宜一点’ -> CONTINUE plus relativePreferences [{dimension: PRICE, direction: LOWER}] and a RECOMMENDATION request with topic '', target {}, and fact DETAIL; "
                + "For a conditional fallback such as ‘如果3公里内没有藏式火锅，就改找烧烤’, emit the initial criteria and exactly one RECOMMENDATION request, then conditionalRequirementChanges [{observedRequestId: r1, predicate: {type: RESULT_STATE, expected: EMPTY}, criteria: {cuisine: 烧烤}, cleared: []}]. The fallback criteria belongs only in conditionalRequirementChanges; it must not replace the initial criteria. "
                + "‘介绍一下火锅历史’ -> CONTINUE/NONE plus requests [{requestId: r1, type: GENERAL, topic: 火锅历史, target: {}, fact: DETAIL}]. "
                + "Never invent a city, district, POI, or device location. Leave location criteria untouched unless the user supplied it; device-relative wording is not a POI. References may only be ordinal, focused, named, lastVisible, or task selector; never invent database ids. "
                + "Return every root field required by the schema; use empty arrays when there is no value. "
                + "Use null/omission for untouched criteria and cleared for explicit removal. "
                + "A numeric budget stated as ‘以内’, ‘不超过’, or an unqualified replacement such as ‘预算改成150’ is a hard maximum: emit budgetHard. Use budgetSoft only when the user explicitly expresses a preference such as ‘大约’ or ‘最好在…左右’; never weaken a stated cap into budgetSoft. Explicit removal such as 预算不限/不要预算限制 must use cleared:[BUDGET] and must not emit a replacement budget. "
                + repairInstruction(repair);
        JsonNode response = aiClient.chatCompletion(List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", userTurn)), List.of(tool), choice, "V2_SEMANTIC_INTERPRETATION");
        String arguments = response.path("choices").path(0).path("message").path("tool_calls")
                .path(0).path("function").path("arguments").asText();
        if (arguments.isBlank()) throw new IllegalArgumentException("model returned no semantics");
        try {
            return new CallResult(objectMapper.readTree(arguments), arguments);
        } catch (Exception e) {
            throw new IllegalArgumentException("model returned invalid semantic json", e);
        }
    }
    private void recordEvaluationAttempt(Violation repair, String arguments, String validationResult) {
        Map<String, Object> attempt = new LinkedHashMap<>(); attempt.put("attempt", repair == null ? "FIRST" : "REPAIR"); attempt.put("violation", repair == null ? "NONE" : repair.name());
        if (arguments != null) attempt.put("functionArguments", arguments); attempt.put("validationResult", validationResult); evaluationAttempts.get().add(attempt);
    }
    private record CallResult(JsonNode root, String arguments) { }

    private DiningCriteriaPatch criteriaPatch(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return new DiningCriteriaPatch(null, null, null, null, null, null);
        String city = nonBlankText(node, "city"), district = nonBlankText(node, "district"), poi = nonBlankText(node, "poi");
        DiningCriteria.LocationCriteria location = city != null || district != null || poi != null
                ? new DiningCriteria.LocationCriteria(city, district, poi) : null;
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
                case "COMPARE" -> result.add(new UserRequest.CompareRequest(id, references(item.path("targets")),
                        factTypes(item.path("dimensions"))));
                case "SELECT" -> result.add(new UserRequest.SelectRequest(id, reference(item.path("target"))));
                case "SIMILAR" -> result.add(new UserRequest.SimilarRequest(id, reference(item.path("target"))));
                case "EXPLORE" -> { boolean unbounded = item.path("unboundedContinuation").asBoolean();
                    JsonNode target = item.path("target");
                    if (!unbounded && emptyReference(target)) throw new IllegalArgumentException("bounded explore requires target");
                    result.add(new UserRequest.ExploreRequest(id, emptyReference(target) ? null : reference(target), unbounded)); }
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
        if (node.path("lastVisible").asBoolean(false)) return new EntityReference.LastVisibleRef();
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
    private boolean emptyReference(JsonNode node) { return node == null || node.isMissingNode() || node.isNull() || (node.isObject() && node.isEmpty()); }
    private String repairInstruction(Violation violation) { return switch (violation == null ? Violation.NONE : violation) {
        case NONE -> "";
        case HARD_BUDGET_REQUIRED -> " Your previous output violated HARD_BUDGET_REQUIRED: the user stated an explicit budget cap or replacement; output criteria.budgetHard.";
        case BUDGET_CLEAR_REQUIRED -> " Your previous output violated BUDGET_CLEAR_REQUIRED: the user removed the budget constraint; output cleared:[BUDGET] and no budgetSoft/budgetHard.";
        case CONDITIONAL_FALLBACK_REQUIRED -> " Your previous output violated CONDITIONAL_FALLBACK_REQUIRED: emit a conditional fallback with non-empty fallback criteria or cleared fields.";
        case GENERIC_CONTRACT -> " Your previous output violated the semantic contract. Return a complete function call and obey enums and required fields exactly.";
    }; }
    enum Violation { NONE, HARD_BUDGET_REQUIRED, BUDGET_CLEAR_REQUIRED, CONDITIONAL_FALLBACK_REQUIRED, GENERIC_CONTRACT }
    private static final class SemanticContractViolation extends IllegalArgumentException { private final Violation code; private SemanticContractViolation(Violation code) { super(code.name()); this.code = code; } private Violation code() { return code; } }
    private ObservationPredicate predicate(JsonNode node) {
        String type = requiredText(node, "type");
        return switch (type) {
            case "RESULT_STATE" -> new ObservationPredicate.ResultStateIs(enumValue(ObservationPredicate.ResultState.class, requiredText(node, "expected")));
            case "BOOLEAN" -> new ObservationPredicate.BooleanEquals(node.path("expected").asBoolean());
            case "NUMERIC" -> new ObservationPredicate.NumericCompare(enumValue(ObservationPredicate.NumericOperator.class, requiredText(node, "operator")), decimal(node, "expected"));
            case "CATEGORY" -> new ObservationPredicate.CategoryEquals(enumValue(ObservationPredicate.ObservationCategory.class, requiredText(node, "expected")));
            default -> throw new IllegalArgumentException("unsupported observation predicate");
        };
    }

    private Map<String, Object> schema() {
        Map<String, Object> string = Map.of("type", "string");
        Map<String, Object> number = Map.of("type", "number");
        Map<String, Object> target = objectSchema(Map.of(
                "ordinal", Map.of("type", "integer"), "focused", Map.of("type", "boolean"), "lastVisible", Map.of("type", "boolean"),
                "name", string, "taskSelector", enumString("EARLIEST", "ACTIVE", "MATCH_CONTEXT"),
                "goalCategory", string, "city", string));
        Map<String, Object> criteria = Map.of("type", "object", "properties", Map.of(
                "city", string, "district", string, "poi", string, "cuisine", string,
                "excludedCuisines", Map.of("type", "array", "items", string),
                "budgetSoft", Map.of("type", "number", "description", "Preference target only; requires explicit approximate/preferred wording"),
                "budgetHard", Map.of("type", "number", "description", "Mandatory maximum for stated budgets and all ‘以内/不超过/改成N’ constraints"),
                "distanceKm", number));
        Map<String, Object> predicate = objectSchema(Map.of("type", enumString("RESULT_STATE", "BOOLEAN", "NUMERIC", "CATEGORY"),
                "expected", string, "operator", enumString(ObservationPredicate.NumericOperator.values())), "type", "expected");
        Map<String, Object> relation = objectSchema(Map.of("observedRequestId", string,
                "dependentRequestId", string, "predicate", predicate), "observedRequestId", "dependentRequestId", "predicate");
        Map<String, Object> conditional = objectSchema(Map.of("observedRequestId", string, "predicate", predicate, "criteria", criteria,
                "cleared", Map.of("type", "array", "items", enumString(RequirementChange.ClearedCriterion.values()))),
                "observedRequestId", "predicate", "criteria", "cleared");
        Map<String, Object> request = objectSchema(Map.of("requestId", string,
                "type", enumString("RECOMMENDATION", "ALTERNATIVES", "FACT", "COMPARE", "SELECT", "SIMILAR", "EXPLORE", "GENERAL"),
                "target", target, "count", Map.of("type", "integer"),
                "fact", enumString(UserRequest.FactType.values()),
                "targets", Map.of("type", "array", "items", target),
                "dimensions", Map.of("type", "array", "items", enumString(UserRequest.FactType.values())),
                "topic", Map.of("type", "string", "description", "Non-empty when type is GENERAL; empty string otherwise"),
                "unboundedContinuation", Map.of("type", "boolean")), "requestId", "type", "topic", "target", "fact");
        Map<String, Object> relativePreference = objectSchema(Map.of(
                "dimension", enumString(DiningCriteria.PreferenceDimension.values()),
                "direction", enumString(RequirementChange.Direction.values())), "dimension", "direction");
        Map<String, Object> feedback = objectSchema(Map.of("target", target,
                "kind", enumString(EntityFeedback.FeedbackKind.values()),
                "aspect", enumString(EntityFeedback.FeedbackAspect.values()),
                "batch", Map.of("type", "boolean"), "polarity", enumString(EntityFeedback.BatchPolarity.values())),
                "target", "kind", "aspect", "batch", "polarity");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("taskDirective", enumString(TaskDirective.values()));
        properties.put("taskDirectiveEvidence", enumString("NONE", "EXPLICIT_NEW_TASK", "EXPLICIT_RESTORE", "EXPLICIT_ABANDON"));
        properties.put("criteria", criteria);
        properties.put("cleared", Map.of("type", "array", "items", enumString(RequirementChange.ClearedCriterion.values())));
        properties.put("relativePreferences", Map.of("type", "array", "items", relativePreference));
        properties.put("relaxationAuthorizations", Map.of("type", "array", "items", enumString(DiningCriteria.PreferenceDimension.values())));
        properties.put("requirementLocks", Map.of("type", "array", "items", enumString(DiningCriteria.PreferenceDimension.values())));
        properties.put("feedback", Map.of("type", "array", "items", feedback));
        properties.put("requests", Map.of("type", "array", "items", request));
        properties.put("relations", Map.of("type", "array", "items", relation));
        properties.put("conditionalRequirementChanges", Map.of("type", "array", "items", conditional));
        properties.put("references", Map.of("type", "array", "items", target));
        return objectSchema(properties, "taskDirective", "taskDirectiveEvidence", "cleared", "relativePreferences", "relaxationAuthorizations",
                "requirementLocks", "feedback", "requests", "relations", "conditionalRequirementChanges", "references");
    }

    /** 闭集枚举必须放进 JSON Schema；只用自然语言提示时模型曾输出小写自由词，导致运行时拒绝。 */
    private Map<String, Object> enumString(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }

    private Map<String, Object> enumString(Enum<?>... values) {
        return enumString(Arrays.stream(values).map(Enum::name).toArray(String[]::new));
    }

    private Map<String, Object> objectSchema(Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        if (required.length > 0) schema.put("required", List.of(required));
        return schema;
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
    private String nonBlankText(JsonNode node, String field) { String value = text(node, field); return value == null || value.isBlank() ? null : value; }
    private boolean any(JsonNode node, String... fields) { for (String field : fields) if (node.hasNonNull(field)) return true; return false; }
    private List<String> strings(JsonNode node) { List<String> result = new ArrayList<>(); for (JsonNode item : node) result.add(item.asText()); return result; }
    private List<UserRequest.FactType> factTypes(JsonNode node) { List<UserRequest.FactType> result = new ArrayList<>(); for (JsonNode item : node) result.add(enumValue(UserRequest.FactType.class, item.asText())); return result; }
    private BigDecimal decimal(JsonNode node, String field) { return node.hasNonNull(field) ? node.path(field).decimalValue() : null; }
}
