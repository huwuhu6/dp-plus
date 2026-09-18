package com.hmdp.ai.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.v2.runtime.SemanticInterpreter;
import com.hmdp.ai.v2.runtime.SemanticInterpretationException;
import com.hmdp.ai.v2.runtime.SemanticModelAvailabilityException;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.v2.semantic.EntityFeedback;
import com.hmdp.ai.v2.semantic.EntityReference;
import com.hmdp.ai.v2.semantic.RequirementChange;
import com.hmdp.ai.v2.semantic.TaskDirective;
import com.hmdp.ai.v2.semantic.UserRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SemanticInterpreterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesOnlySemanticReferencesAndCriteriaWithoutDurableIdentities() throws Exception {
        SemanticInterpreter interpreter = new SemanticInterpreter();
        var semantics = interpreter.parse(mapper.readTree("""
                {
                  "taskDirective":"CONTINUE",
                  "taskDirectiveEvidence":"NONE",
                  "criteria":{"city":"福州","cuisine":"烧烤","budgetHard":100},
                  "relativePreferences":[{"dimension":"DISTANCE","direction":"LOWER"}],
                  "feedback":[{"target":{"ordinal":2},"kind":"CRITIQUE","aspect":"PRICE","batch":false,"polarity":"NEGATIVE"}],
                  "requests":[{"requestId":"r1","type":"FACT","target":{"ordinal":2},"fact":"SOCKET"}]
                }
                """));

        RequirementChange.CriteriaPatch patch = assertInstanceOf(RequirementChange.CriteriaPatch.class,
                semantics.requirementChanges().getFirst());
        assertEquals("福州", patch.patch().location().city());
        assertEquals("烧烤", patch.patch().cuisine().include());
        assertEquals(new BigDecimal("100"), patch.patch().budget().hardMax());
        EntityFeedback.EntityFeedbackItem feedback = assertInstanceOf(EntityFeedback.EntityFeedbackItem.class,
                semantics.entityFeedback().getFirst());
        assertEquals(2, assertInstanceOf(EntityReference.OrdinalRef.class, feedback.target()).ordinal());
        UserRequest.FactQueryRequest request = assertInstanceOf(UserRequest.FactQueryRequest.class, semantics.requests().getFirst());
        assertEquals(UserRequest.FactType.SOCKET, request.fact());
    }

    @Test
    void parsesExplicitClearAndTaskSelectionAsSemanticValues() throws Exception {
        SemanticInterpreter interpreter = new SemanticInterpreter();
        var semantics = interpreter.parse(mapper.readTree("""
                {
                  "taskDirective":"RESTORE",
                  "taskDirectiveEvidence":"EXPLICIT_RESTORE",
                  "cleared":["BUDGET"],
                  "references":[{"taskSelector":"MATCH_CONTEXT","goalCategory":"DINING","city":"福州"}]
                }
                """));

        RequirementChange.CriteriaPatch patch = assertInstanceOf(RequirementChange.CriteriaPatch.class,
                semantics.requirementChanges().getFirst());
        assertEquals(java.util.Set.of(RequirementChange.ClearedCriterion.BUDGET), patch.cleared());
        EntityReference.TaskRef task = assertInstanceOf(EntityReference.TaskRef.class, semantics.references().getFirst());
        assertInstanceOf(com.hmdp.ai.v2.semantic.TaskSelector.MatchContext.class, task.selector());
    }

    @Test
    void parsesCompareLastVisibleAndKeepsBlankLocationUntouched() throws Exception {
        SemanticInterpreter interpreter = new SemanticInterpreter();
        var semantics = interpreter.parse(mapper.readTree("""
                {"taskDirective":"CONTINUE","taskDirectiveEvidence":"NONE",
                 "criteria":{"city":"","poi":""},
                 "requests":[{"requestId":"compare","type":"COMPARE","target":{},"fact":"DETAIL",
                   "targets":[{"ordinal":1},{"lastVisible":true}],"dimensions":["DISTANCE","REVIEW"]}]}
                """));
        RequirementChange.CriteriaPatch patch = assertInstanceOf(RequirementChange.CriteriaPatch.class,
                semantics.requirementChanges().getFirst());
        assertEquals(null, patch.patch().location());
        UserRequest.CompareRequest compare = assertInstanceOf(UserRequest.CompareRequest.class, semantics.requests().getFirst());
        assertEquals(2, compare.targets().size());
        assertInstanceOf(EntityReference.LastVisibleRef.class, compare.targets().get(1));
        assertEquals(List.of(UserRequest.FactType.DISTANCE, UserRequest.FactType.REVIEW), compare.dimensions());
    }

    @Test
    void preservesHardBudgetAndParsesUnboundedExploreContract() throws Exception {
        SemanticInterpreter interpreter = new SemanticInterpreter();
        var budget = interpreter.parse(mapper.readTree("""
                {"taskDirective":"CONTINUE","taskDirectiveEvidence":"NONE",
                 "criteria":{"budgetHard":150},"requests":[{"requestId":"r","type":"RECOMMENDATION","target":{},"fact":"DETAIL","topic":""}]}
                """));
        RequirementChange.CriteriaPatch budgetPatch = assertInstanceOf(RequirementChange.CriteriaPatch.class,
                budget.requirementChanges().getFirst());
        assertEquals(new BigDecimal("150"), budgetPatch.patch().budget().hardMax());
        var explore = interpreter.parse(mapper.readTree("""
                {"taskDirective":"CONTINUE","taskDirectiveEvidence":"NONE",
                 "requests":[{"requestId":"e","type":"EXPLORE","target":{"lastVisible":true},"fact":"EVIDENCE","topic":"","unboundedContinuation":true}]}
                """));
        UserRequest.ExploreRequest request = assertInstanceOf(UserRequest.ExploreRequest.class, explore.requests().getFirst());
        assertTrue(request.unboundedContinuation());
        assertInstanceOf(EntityReference.LastVisibleRef.class, request.target());
    }

    @Test
    void detectsBudgetCapsAndExplicitClearsAcrossSemanticForms() {
        SemanticInterpreter interpreter = new SemanticInterpreter();
        for (String message : List.of("预算改成150", "预算不超过120", "人均80以内", "消费最多90元"))
            assertTrue((Boolean) ReflectionTestUtils.invokeMethod(interpreter, "requiresHardBudget", message), message);
        for (String message : List.of("不要预算限制", "去掉预算条件"))
            assertTrue((Boolean) ReflectionTestUtils.invokeMethod(interpreter, "requiresBudgetClear", message), message);
    }

    @Test
    void blankOptionalCuisineIsUntouchedButExclusionsRemainMeaningful() throws Exception {
        SemanticInterpreter interpreter = new SemanticInterpreter();
        RequirementChange.CriteriaPatch blank = assertInstanceOf(RequirementChange.CriteriaPatch.class, interpreter.parse(mapper.readTree("""
                {"taskDirective":"CONTINUE","taskDirectiveEvidence":"NONE","criteria":{"cuisine":"   "}}
                """)).requirementChanges().getFirst());
        assertEquals(null, blank.patch().cuisine());
        RequirementChange.CriteriaPatch excluded = assertInstanceOf(RequirementChange.CriteriaPatch.class, interpreter.parse(mapper.readTree("""
                {"taskDirective":"CONTINUE","taskDirectiveEvidence":"NONE","criteria":{"cuisine":"","excludedCuisines":["烧烤"," "]}}
                """)).requirementChanges().getFirst());
        assertEquals(null, excluded.patch().cuisine().include()); assertEquals(List.of("烧烤"), excluded.patch().cuisine().exclude());
    }

    @Test
    void cuisineCoverageRequiresRepairOnlyForRecommendationWithoutCuisineSemantics() throws Exception {
        SemanticInterpreter interpreter = new SemanticInterpreter();
        var omitted = interpreter.parse(mapper.readTree("""
                {"taskDirective":"CONTINUE","taskDirectiveEvidence":"NONE","requests":[{"requestId":"r","type":"RECOMMENDATION","target":{},"fact":"DETAIL","topic":""}]}
                """));
        for (String input : List.of("想吃寿司", "找烤肉", "来点川菜", "附近喝咖啡", "想吃面食"))
            assertTrue((Boolean) ReflectionTestUtils.invokeMethod(interpreter, "requiresCuisineCoverage", input, omitted), input);
        var exclusion = interpreter.parse(mapper.readTree("""
                {"taskDirective":"CONTINUE","taskDirectiveEvidence":"NONE","criteria":{"excludedCuisines":["火锅"]},"requests":[{"requestId":"r","type":"RECOMMENDATION","target":{},"fact":"DETAIL","topic":""}]}
                """));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(interpreter, "requiresCuisineCoverage", "不吃火锅，换点别的", exclusion));
        var general = interpreter.parse(mapper.readTree("""
                {"taskDirective":"CONTINUE","taskDirectiveEvidence":"NONE","requests":[{"requestId":"g","type":"GENERAL","target":{},"fact":"DETAIL","topic":"火锅历史"}]}
                """));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(interpreter, "requiresCuisineCoverage", "介绍火锅历史", general));
    }

    @Test
    void locationProvenanceDropsInventedPlacesButPreservesExplicitMentions() throws Exception {
        SemanticInterpreter interpreter = new SemanticInterpreter();
        var parsed = interpreter.parse(mapper.readTree("""
                {"taskDirective":"CONTINUE","taskDirectiveEvidence":"NONE","criteria":{"poi":"虚构地点","cuisine":"火锅","budgetHard":120,"distanceKm":3}}
                """));
        var dropped = (com.hmdp.ai.v2.semantic.TurnSemantics) ReflectionTestUtils.invokeMethod(interpreter, "normalizeLocationProvenance", "找3公里内人均120以内火锅", parsed);
        RequirementChange.CriteriaPatch patch = assertInstanceOf(RequirementChange.CriteriaPatch.class, dropped.requirementChanges().getFirst());
        assertEquals(null, patch.patch().location()); assertEquals("火锅", patch.patch().cuisine().include()); assertEquals(new BigDecimal("120"), patch.patch().budget().hardMax());
        var city = (com.hmdp.ai.v2.semantic.TurnSemantics) ReflectionTestUtils.invokeMethod(interpreter, "normalizeLocationProvenance", "福州附近找火锅", interpreter.parse(mapper.readTree("""
                {"taskDirective":"CONTINUE","taskDirectiveEvidence":"NONE","criteria":{"city":"福州市"}}
                """)));
        assertEquals("福州市", assertInstanceOf(RequirementChange.CriteriaPatch.class, city.requirementChanges().getFirst()).patch().location().city());

        var deviceRelative = (com.hmdp.ai.v2.semantic.TurnSemantics) ReflectionTestUtils.invokeMethod(interpreter, "normalizeLocationProvenance", "附近找火锅", interpreter.parse(mapper.readTree("""
                {"taskDirective":"CONTINUE","taskDirectiveEvidence":"NONE","criteria":{"poi":"device_location_relative"}}
                """)));
        assertEquals(null, assertInstanceOf(RequirementChange.CriteriaPatch.class, deviceRelative.requirementChanges().getFirst()).patch().location());
        var namedPoi = (com.hmdp.ai.v2.semantic.TurnSemantics) ReflectionTestUtils.invokeMethod(interpreter, "normalizeLocationProvenance", "福州大学附近找火锅", interpreter.parse(mapper.readTree("""
                {"taskDirective":"CONTINUE","taskDirectiveEvidence":"NONE","criteria":{"poi":"福州大学"}}
                """)));
        assertEquals("福州大学", assertInstanceOf(RequirementChange.CriteriaPatch.class, namedPoi.requirementChanges().getFirst()).patch().location().poi());
    }

    @Test
    void locationProvenanceRejectsCrossSlotCopiesWithoutBlacklistingNamedPlaces() throws Exception {
        SemanticInterpreter interpreter = new SemanticInterpreter();
        assertEquals(null, normalizedPoi(interpreter, "人均240以内找餐厅", "240", "240", null, null));
        assertEquals(null, normalizedPoi(interpreter, "3公里内找烧烤", "烧烤", null, null, "3"));
        assertEquals(null, normalizedPoi(interpreter, "附近找餐厅", "device_location_relative", null, null, null));
        assertEquals(null, normalizedPoi(interpreter, "找餐厅", "不存在地点", null, null, null));
        assertEquals("北京路", normalizedPoi(interpreter, "广州北京路附近找粤菜", "北京路", null, null, null));
        assertEquals("南京路步行街", normalizedPoi(interpreter, "南京路步行街附近找餐厅", "南京路步行街", null, null, null));
        assertEquals("中山大学", normalizedPoi(interpreter, "中山大学附近找餐厅", "中山大学", null, null, null));
        assertEquals("北京798艺术区", normalizedPoi(interpreter, "北京798艺术区附近找咖啡", "北京798艺术区", null, null, null));
        assertEquals("三里屯", normalizedPoi(interpreter, "人均180以内，在三里屯附近找火锅", "三里屯", "180", null, null));
    }

    private String normalizedPoi(SemanticInterpreter interpreter, String userTurn, String poi, String hardBudget, String softBudget, String distance) throws Exception {
        String fields = "\"poi\":\"%s\"".formatted(poi)
                + (hardBudget == null ? "" : ",\"budgetHard\":%s".formatted(hardBudget))
                + (softBudget == null ? "" : ",\"budgetSoft\":%s".formatted(softBudget))
                + (distance == null ? "" : ",\"distanceKm\":%s".formatted(distance));
        var parsed = interpreter.parse(mapper.readTree("{\"taskDirective\":\"CONTINUE\",\"taskDirectiveEvidence\":\"NONE\",\"criteria\":{" + fields + "}}"));
        var normalized = (com.hmdp.ai.v2.semantic.TurnSemantics) ReflectionTestUtils.invokeMethod(interpreter, "normalizeLocationProvenance", userTurn, parsed);
        var location = assertInstanceOf(RequirementChange.CriteriaPatch.class, normalized.requirementChanges().getFirst()).patch().location();
        return location == null ? null : location.poi();
    }

    @Test
    void transportFailureIsObservedWithoutSemanticRepair() {
        SemanticInterpreter interpreter = new SemanticInterpreter();
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ReflectionTestUtils.setField(interpreter, "aiClient", client);
        ReflectionTestUtils.setField(interpreter, "objectMapper", mapper);
        when(client.chatCompletion(any(List.class), any(List.class), any(Map.class), anyString()))
                .thenThrow(new ResourceAccessException("read timed out", new SocketTimeoutException("timed out")));

        assertInstanceOf(SemanticModelAvailabilityException.class,
                org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> interpreter.interpret("找火锅")));
        verify(client, times(1)).chatCompletion(any(List.class), any(List.class), any(Map.class), anyString());
        assertEquals("MODEL_TRANSPORT_FAILURE", interpreter.takeEvaluationAttempts().getFirst().get("validationResult"));
    }

    @Test
    void unboundedExploreAllowsEmptyTargetButBoundedExploreRejectsIt() throws Exception {
        SemanticInterpreter interpreter = new SemanticInterpreter();
        var semantics = interpreter.parse(mapper.readTree("""
                {"taskDirective":"CONTINUE","taskDirectiveEvidence":"NONE",
                 "requests":[{"requestId":"e","type":"EXPLORE","target":{},"fact":"EVIDENCE","topic":"","unboundedContinuation":true}]}
                """));
        UserRequest.ExploreRequest request = assertInstanceOf(UserRequest.ExploreRequest.class, semantics.requests().getFirst());
        assertTrue(request.unboundedContinuation()); assertEquals(null, request.target());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> interpreter.parse(mapper.readTree("""
                {"taskDirective":"CONTINUE","taskDirectiveEvidence":"NONE",
                 "requests":[{"requestId":"e","type":"EXPLORE","target":{},"fact":"EVIDENCE","topic":"","unboundedContinuation":false}]}
                """)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void repairUsesClosedViolationInstructionAndRetainsBothFailures() throws Exception {
        SemanticInterpreter interpreter = new SemanticInterpreter();
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ReflectionTestUtils.setField(interpreter, "aiClient", client);
        ReflectionTestUtils.setField(interpreter, "objectMapper", mapper);
        var invalid = modelResponse("""
                {"taskDirective":"CONTINUE","taskDirectiveEvidence":"NONE","requests":[]}
                """);
        when(client.chatCompletion(any(List.class), any(List.class), any(Map.class), anyString())).thenReturn(invalid, invalid);
        SemanticInterpretationException error = org.junit.jupiter.api.Assertions.assertThrows(SemanticInterpretationException.class,
                () -> interpreter.interpret("如果没有火锅就改烧烤"));
        assertEquals("CONDITIONAL_FALLBACK_REQUIRED", error.getCause().getMessage());
        assertEquals(1, error.getSuppressed().length);
        assertEquals("CONDITIONAL_FALLBACK_REQUIRED", error.getSuppressed()[0].getMessage());
        ArgumentCaptor<List<Map<String, Object>>> messages = ArgumentCaptor.forClass(List.class);
        verify(client, times(2)).chatCompletion(messages.capture(), any(List.class), any(Map.class), anyString());
        assertTrue(String.valueOf(messages.getAllValues().get(1).getFirst().get("content")).contains("CONDITIONAL_FALLBACK_REQUIRED"));
        assertFalse(String.valueOf(messages.getAllValues().get(1).getFirst().get("content")).contains("HARD_BUDGET_REQUIRED"));
    }

    private com.fasterxml.jackson.databind.JsonNode modelResponse(String arguments) throws Exception {
        return mapper.readTree("""
                {"choices":[{"message":{"tool_calls":[{"function":{"arguments":%s}}]}}]}
                """.formatted(mapper.writeValueAsString(arguments)));
    }

    @Test
    void allowsRestoreWithoutSelectorForRuntimeClarification() throws Exception {
        SemanticInterpreter interpreter = new SemanticInterpreter();
        var semantics = interpreter.parse(mapper.readTree("""
                {"taskDirective":"RESTORE","taskDirectiveEvidence":"EXPLICIT_RESTORE"}
                """));
        assertEquals(TaskDirective.RESTORE, semantics.taskDirective());
        assertTrue(semantics.references().isEmpty());
    }

    @Test
    void parsesConditionalFallbackAsAnObservationBoundCriteriaChange() throws Exception {
        SemanticInterpreter interpreter = new SemanticInterpreter();
        var semantics = interpreter.parse(mapper.readTree("""
                {
                  "taskDirective":"CONTINUE",
                  "taskDirectiveEvidence":"NONE",
                  "criteria":{"cuisine":"藏式火锅","distanceKm":3},
                  "requests":[{"requestId":"r1","type":"RECOMMENDATION"}],
                  "conditionalRequirementChanges":[{
                    "observedRequestId":"r1",
                    "predicate":{"type":"RESULT_STATE","expected":"EMPTY"},
                    "criteria":{"cuisine":"烧烤"},
                    "cleared":[]
                  }]
                }
                """));

        RequirementChange.ConditionalRequirementChange change = assertInstanceOf(
                RequirementChange.ConditionalRequirementChange.class, semantics.requirementChanges().get(1));
        assertEquals("r1", change.observedRequestId());
        assertEquals("烧烤", change.change().patch().cuisine().include());
    }

    @Test
    void recognizesAConditionalFallbackPhraseForSemanticRepair() {
        SemanticInterpreter interpreter = new SemanticInterpreter();
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(interpreter, "requiresConditionalFallback",
                "如果附近没有火锅，就改找烧烤"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void modelSchemaConstrainsCanonicalEnumsAndRequiredRequestFields() {
        SemanticInterpreter interpreter = new SemanticInterpreter();
        Map<String, Object> schema = (Map<String, Object>) ReflectionTestUtils.invokeMethod(interpreter, "schema");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertEquals(List.of("taskDirective", "taskDirectiveEvidence", "cleared", "relativePreferences", "relaxationAuthorizations",
                "requirementLocks", "feedback", "requests", "relations", "conditionalRequirementChanges", "references"),
                schema.get("required"));

        Map<String, Object> directive = (Map<String, Object>) properties.get("taskDirective");
        assertEquals(Arrays.stream(TaskDirective.values()).map(Enum::name).toList(), directive.get("enum"));

        Map<String, Object> relativePreferences = (Map<String, Object>) properties.get("relativePreferences");
        Map<String, Object> relativeItem = (Map<String, Object>) relativePreferences.get("items");
        assertEquals(List.of("dimension", "direction"), relativeItem.get("required"));

        Map<String, Object> feedback = (Map<String, Object>) properties.get("feedback");
        Map<String, Object> feedbackItem = (Map<String, Object>) feedback.get("items");
        assertEquals(List.of("target", "kind", "aspect", "batch", "polarity"), feedbackItem.get("required"));

        Map<String, Object> requests = (Map<String, Object>) properties.get("requests");
        Map<String, Object> requestItem = (Map<String, Object>) requests.get("items");
        assertEquals(List.of("requestId", "type", "topic", "target", "fact"), requestItem.get("required"));
        Map<String, Object> requestProperties = (Map<String, Object>) requestItem.get("properties");
        assertEquals(List.of("RECOMMENDATION", "ALTERNATIVES", "FACT", "COMPARE", "SELECT", "SIMILAR", "EXPLORE", "GENERAL"),
                ((Map<String, Object>) requestProperties.get("type")).get("enum"));

        Map<String, Object> conditionals = (Map<String, Object>) properties.get("conditionalRequirementChanges");
        Map<String, Object> conditionalItem = (Map<String, Object>) conditionals.get("items");
        assertEquals(List.of("observedRequestId", "predicate", "criteria", "cleared"), conditionalItem.get("required"));
    }

    @Test
    void rejectsTaskDirectiveWithoutMatchingExplicitEvidence() throws Exception {
        SemanticInterpreter interpreter = new SemanticInterpreter();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> interpreter.parse(mapper.readTree("""
                {"taskDirective":"START_NEW","taskDirectiveEvidence":"NONE"}
                """)));
    }
}
