package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.dto.DecisionConstraints;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        JsonNode modelResponse = objectMapper.readTree("{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"targetCity\\\":\\\"重庆\\\",\\\"targetArea\\\":\\\"解放碑\\\",\\\"keyword\\\":\\\"火锅\\\",\\\"cuisine\\\":\\\"港式茶餐厅\\\",\\\"budgetPerPerson\\\":100,\\\"radiusKm\\\":-1,\\\"nearby\\\":false,\\\"arrivalTime\\\":\\\"\\\",\\\"preferences\\\":[\\\"情侣约会\\\"],\\\"missingInformation\\\":[]}\"}}]}}]}");
        when(client.chatCompletion(any(), any(), any(), any())).thenReturn(modelResponse);

        DecisionConstraints constraints = extractor.extract("人均100的港式茶餐厅");

        assertEquals("港式", constraints.getCuisine());
        assertEquals("重庆", constraints.getTargetCity());
        assertEquals("解放碑", constraints.getTargetArea());
        assertEquals("火锅", constraints.getKeyword());
        assertEquals(Integer.valueOf(100), constraints.getBudgetPerPerson());
        assertTrue(constraints.getPreferences().contains("约会"));
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

    @Test
    void currentDeviceIntentClearsConflictingTargetSlotsFromModel() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ConstraintExtractor extractor = new ConstraintExtractor();
        ReflectionTestUtils.setField(extractor, "aiClient", client);
        ReflectionTestUtils.setField(extractor, "objectMapper", objectMapper);
        JsonNode modelResponse = objectMapper.readTree("{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"locationIntent\\\":\\\"CURRENT_DEVICE\\\",\\\"targetCity\\\":\\\"北京\\\",\\\"targetArea\\\":\\\"朝阳区\\\",\\\"keyword\\\":\\\"烧烤\\\",\\\"cuisine\\\":\\\"烧烤\\\",\\\"budgetPerPerson\\\":-1,\\\"radiusKm\\\":-1,\\\"nearby\\\":true,\\\"arrivalTime\\\":\\\"\\\",\\\"preferences\\\":[],\\\"missingInformation\\\":[]}\"}}]}}]}");
        when(client.chatCompletion(any(), any(), any(), any())).thenReturn(modelResponse);

        DecisionConstraints constraints = extractor.extract("看看我附近的烧烤");

        assertEquals("CURRENT_DEVICE", constraints.getLocationIntent());
        assertEquals("", constraints.getTargetCity());
        assertEquals("", constraints.getTargetArea());
    }

    @Test
    void preservesModelExtractedClearedFields() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ConstraintExtractor extractor = new ConstraintExtractor();
        ReflectionTestUtils.setField(extractor, "aiClient", client);
        ReflectionTestUtils.setField(extractor, "objectMapper", objectMapper);
        JsonNode modelResponse = objectMapper.readTree("{\"choices\": [{\"message\": {\"tool_calls\": [{\"function\": {\"arguments\": \"{\\\"targetCity\\\": \\\"\\\", \\\"targetArea\\\": \\\"\\\", \\\"locationIntent\\\": \\\"UNSPECIFIED\\\", \\\"keyword\\\": \\\"\\\", \\\"cuisine\\\": \\\"\\\", \\\"budgetPerPerson\\\": -1, \\\"radiusKm\\\": -1, \\\"nearby\\\": false, \\\"arrivalTime\\\": \\\"\\\", \\\"preferences\\\": [], \\\"missingInformation\\\": [], \\\"clearedFields\\\": [\\\"cuisine\\\", \\\"keyword\\\"]}\"}}]}}]}");

        when(client.chatCompletion(any(), any(), any(), any())).thenReturn(modelResponse);

        DecisionConstraints constraints = extractor.extract("\u770b\u770b\u6709\u6ca1\u6709\u522b\u7684\u5403\u7684");

        assertEquals(java.util.Arrays.asList("cuisine", "keyword"), constraints.getClearedFields());
    }

}
