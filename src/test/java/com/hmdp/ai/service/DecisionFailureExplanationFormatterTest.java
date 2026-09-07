package com.hmdp.ai.service;

import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.DecisionResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DecisionFailureExplanationFormatterTest {
    @Test
    void canonicalNamedPoiWinsOverNearbyInFailureScope() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setNearby(true);
        constraints.setRadiusKm(5D);
        constraints.setKeyword("兰州拉面");
        constraints.setCuisine("面食");
        DecisionResponse response = new DecisionResponse();
        response.setStatus("WAITING_RELAXATION");
        response.setConstraints(constraints);

        String answer = new DecisionFailureExplanationFormatter().format(response, "福建理工大学旗山校区");

        assertTrue(answer.contains("福建理工大学旗山校区附近 5km"));
        assertTrue(answer.contains("兰州拉面"));
        assertFalse(answer.contains("当前位置附近"));
    }
}
