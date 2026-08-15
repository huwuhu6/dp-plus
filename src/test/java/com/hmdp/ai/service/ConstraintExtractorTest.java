package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.dto.DecisionConstraints;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConstraintExtractorTest {
    @Test
    void normalizesModelCuisineAliasesBeforeRetrieval() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ConstraintExtractor extractor = new ConstraintExtractor();
        ReflectionTestUtils.setField(extractor, "aiClient", client);
        ReflectionTestUtils.setField(extractor, "objectMapper", objectMapper);
        JsonNode modelResponse = objectMapper.readTree("{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"cuisine\\\":\\\"港式茶餐厅\\\",\\\"budgetPerPerson\\\":100,\\\"radiusKm\\\":-1,\\\"nearby\\\":false,\\\"arrivalTime\\\":\\\"\\\",\\\"occasion\\\":\\\"情侣约会\\\",\\\"quiet\\\":false,\\\"avoidQueue\\\":false,\\\"hardConstraints\\\":[],\\\"softPreferences\\\":[],\\\"missingInformation\\\":[]}\"}}]}}]}");
        when(client.chatCompletion(any(), any(), any(), any())).thenReturn(modelResponse);

        DecisionConstraints constraints = extractor.extract("人均100的港式茶餐厅");

        assertEquals("港式", constraints.getCuisine());
        assertEquals(Integer.valueOf(100), constraints.getBudgetPerPerson());
        assertEquals("约会", constraints.getOccasion());
    }

    @Test
    void convertsMeterRadiusWhenModelExtractionFallsBackToRules() {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ConstraintExtractor extractor = new ConstraintExtractor();
        ReflectionTestUtils.setField(extractor, "aiClient", client);
        ReflectionTestUtils.setField(extractor, "objectMapper", objectMapper);
        when(client.chatCompletion(any(), any(), any(), any())).thenThrow(new IllegalStateException("model unavailable"));

        DecisionConstraints constraints = extractor.extract("附近100米的日料");

        assertEquals("日料", constraints.getCuisine());
        assertEquals(0.1D, constraints.getRadiusKm());
        assertEquals(true, constraints.getNearby());
    }
}
