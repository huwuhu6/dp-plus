package com.hmdp.ai.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.v2.runtime.SemanticInterpreter;
import com.hmdp.ai.v2.semantic.EntityFeedback;
import com.hmdp.ai.v2.semantic.EntityReference;
import com.hmdp.ai.v2.semantic.RequirementChange;
import com.hmdp.ai.v2.semantic.UserRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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
                  "feedback":[{"target":{"ordinal":2},"kind":"CRITIQUE","aspect":"PRICE"}],
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
}
