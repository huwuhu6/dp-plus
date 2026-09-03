package com.hmdp.ai.service;

import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.DecisionRequest;
import com.hmdp.ai.dto.RelaxationInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultEvaluationServiceTest {
    private ResultEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new ResultEvaluationService();
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
    }

    @Test
    void expandsOnlyTheSystemDefaultNearbyRadiusAfterAnEmptyStrictSearch() {
        DecisionRequest request = nearbyRequest();
        DecisionConstraints constraints = defaultNearbyConstraints();

        RelaxationInfo result = service.evaluateStrictResult(request, constraints, 0);
        boolean applied = service.applySafeAutomaticRelaxation(request, constraints, result);
        service.recordRelaxedResult(result, 1);

        assertTrue(applied);
        assertEquals(5D, constraints.getRadiusKm());
        assertEquals("AUTO_RELAXED", result.getOutcome());
        assertTrue(result.getAppliedChanges().contains("radiusKm:3->5 (SYSTEM_DEFAULT)"));
        assertTrue(result.getPreservedHardConstraints().contains("location"));
        assertTrue(result.getPreservedHardConstraints().contains("cuisine=火锅"));
        assertTrue(result.getPreservedHardConstraints().contains("budgetPerPerson=150"));
    }

    @Test
    void neverExpandsAnExplicitRadiusOrOtherHardConstraint() {
        DecisionRequest request = nearbyRequest();
        DecisionConstraints constraints = defaultNearbyConstraints();
        constraints.getSystemNotes().clear();

        RelaxationInfo result = service.evaluateStrictResult(request, constraints, 0);
        boolean applied = service.applySafeAutomaticRelaxation(request, constraints, result);

        assertFalse(applied);
        assertEquals(3D, constraints.getRadiusKm());
        assertEquals("EMPTY", result.getOutcome());
    }

    private DecisionRequest nearbyRequest() {
        DecisionRequest request = new DecisionRequest();
        request.setLatitude(26.04D);
        request.setLongitude(119.20D);
        request.setQuery("附近的火锅");
        return request;
    }

    private DecisionConstraints defaultNearbyConstraints() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setNearby(true);
        constraints.setRadiusKm(3D);
        constraints.setCuisine("火锅");
        constraints.setBudgetPerPerson(150);
        constraints.getSystemNotes().add("“附近”按默认 3km 解释");
        return constraints;
    }
}
