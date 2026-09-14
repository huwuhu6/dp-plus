package com.hmdp.ai.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.v2.runtime.SemanticInterpreter;
import com.hmdp.ai.v2.semantic.EntityFeedback;
import com.hmdp.ai.v2.semantic.EntityReference;
import com.hmdp.ai.v2.semantic.RequirementChange;
import com.hmdp.ai.v2.semantic.TaskDirective;
import com.hmdp.ai.v2.semantic.UserRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticInterpreterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesOnlySemanticReferencesAndCriteriaWithoutDurableIdentities() throws Exception {
        SemanticInterpreter interpreter = new SemanticInterpreter();
        var semantics = interpreter.parse(mapper.readTree("""
                {
                  "taskDirective":"CONTINUE",
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
    void parsesConditionalFallbackAsAnObservationBoundCriteriaChange() throws Exception {
        SemanticInterpreter interpreter = new SemanticInterpreter();
        var semantics = interpreter.parse(mapper.readTree("""
                {
                  "taskDirective":"CONTINUE",
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
        assertEquals(List.of("taskDirective", "cleared", "relativePreferences", "relaxationAuthorizations",
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
        assertEquals(List.of("RECOMMENDATION", "ALTERNATIVES", "FACT", "SELECT", "SIMILAR", "EXPLORE", "GENERAL"),
                ((Map<String, Object>) requestProperties.get("type")).get("enum"));

        Map<String, Object> conditionals = (Map<String, Object>) properties.get("conditionalRequirementChanges");
        Map<String, Object> conditionalItem = (Map<String, Object>) conditionals.get("items");
        assertEquals(List.of("observedRequestId", "predicate", "criteria", "cleared"), conditionalItem.get("required"));
    }
}
