package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.ConstraintSource;
import com.hmdp.ai.geo.AdministrativeRegion;
import com.hmdp.ai.geo.AdministrativeRegionResolver;
import com.hmdp.ai.geo.AdministrativeLevel;
import com.hmdp.ai.geo.ClasspathAdministrativeRegionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConstraintExtractorTest {
    @Test
    void recordsExplicitAndDerivedPreferenceSourceHintsDuringExtraction() {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ConstraintExtractor extractor = new ConstraintExtractor();
        ReflectionTestUtils.setField(extractor, "aiClient", client);
        ReflectionTestUtils.setField(extractor, "objectMapper", objectMapper);
        when(client.chatCompletion(any(), any(), any(), any())).thenThrow(new IllegalStateException("model unavailable"));

        DecisionConstraints explicit = extractor.extract("想找安静的火锅");
        DecisionConstraints derived = extractor.extract("适合聊天的火锅");
        DecisionConstraints explicitDating = extractor.extract("想找适合约会的餐厅");
        DecisionConstraints derivedDating = extractor.extract("和女朋友吃个饭");

        assertEquals(ConstraintSource.USER_EXPLICIT, explicit.getSourceHints().get("preference:安静"));
        assertEquals(ConstraintSource.DERIVED, derived.getSourceHints().get("preference:安静"));
        assertEquals(ConstraintSource.USER_EXPLICIT, explicitDating.getSourceHints().get("preference:约会"));
        assertEquals(ConstraintSource.DERIVED, derivedDating.getSourceHints().get("preference:约会"));
    }

    @Test
    void doesNotPromoteUnverifiedModelDistrictIntoCanonicalAdminDelta() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ConstraintExtractor extractor = new ConstraintExtractor();
        ReflectionTestUtils.setField(extractor, "aiClient", client);
        ReflectionTestUtils.setField(extractor, "objectMapper", objectMapper);
        JsonNode modelResponse = objectMapper.readTree("{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"targetProvince\\\":\\\"\\\",\\\"targetCity\\\":\\\"\\\",\\\"targetDistrict\\\":\\\"连江县\\\",\\\"targetArea\\\":\\\"\\\",\\\"locationIntent\\\":\\\"EXPLICIT_TARGET\\\",\\\"keyword\\\":\\\"\\\",\\\"cuisine\\\":\\\"\\\",\\\"budgetPerPerson\\\":-1,\\\"radiusKm\\\":-1,\\\"nearby\\\":false,\\\"arrivalTime\\\":\\\"\\\",\\\"preferences\\\":[],\\\"missingInformation\\\":[] }\"}}]}}]}");
        when(client.chatCompletion(any(), any(), any(), any())).thenReturn(modelResponse);

        DecisionConstraints constraints = extractor.extract("连江那边有没有好吃的");

        assertEquals("", constraints.getTargetDistrict());
        assertTrue(constraints.getClearedFields().contains("targetDistrict"));
        assertTrue(constraints.getMissingInformation().contains("administrativeRegion"));
    }

    @Test
    void validatesSuffixlessModelHintThroughAdministrativeAuthority() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ConstraintExtractor extractor = new ConstraintExtractor();
        ReflectionTestUtils.setField(extractor, "aiClient", client);
        ReflectionTestUtils.setField(extractor, "objectMapper", objectMapper);
        AdministrativeRegion region = new AdministrativeRegion();
        region.setAdcode("350122"); region.setName("连江县"); region.setLevel(AdministrativeLevel.DISTRICT);
        region.setDistrict("连江县"); region.setCity("福州市"); region.setProvince("福建省");
        com.hmdp.ai.geo.AdministrativeRegionProvider provider = mock(com.hmdp.ai.geo.AdministrativeRegionProvider.class);
        when(provider.resolve(anyString(), any())).thenReturn(List.of(region));
        ReflectionTestUtils.setField(extractor, "administrativeRegionResolver", new AdministrativeRegionResolver(
                new ClasspathAdministrativeRegionRepository(), provider));
        JsonNode modelResponse = objectMapper.readTree("{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"targetProvince\\\":\\\"\\\",\\\"targetCity\\\":\\\"\\\",\\\"targetDistrict\\\":\\\"连江县\\\",\\\"targetArea\\\":\\\"\\\",\\\"locationIntent\\\":\\\"EXPLICIT_TARGET\\\",\\\"keyword\\\":\\\"\\\",\\\"cuisine\\\":\\\"\\\",\\\"budgetPerPerson\\\":-1,\\\"radiusKm\\\":-1,\\\"nearby\\\":false,\\\"arrivalTime\\\":\\\"\\\",\\\"preferences\\\":[],\\\"missingInformation\\\":[] }\"}}]}}]}");
        when(client.chatCompletion(any(), any(), any(), any())).thenReturn(modelResponse);

        DecisionConstraints constraints = extractor.extract("连江那边有没有好吃的");

        assertEquals("福州市", constraints.getTargetCity());
        assertEquals("连江县", constraints.getTargetDistrict());
        verify(provider).resolve("连江县", null);
    }

    @Test
    void canonicalizesModelChatPreferenceAsDerivedQuietPreference() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ConstraintExtractor extractor = new ConstraintExtractor();
        ReflectionTestUtils.setField(extractor, "aiClient", client);
        ReflectionTestUtils.setField(extractor, "objectMapper", objectMapper);
        JsonNode modelResponse = objectMapper.readTree("{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"targetCity\\\":\\\"福州\\\",\\\"targetArea\\\":\\\"\\\",\\\"keyword\\\":\\\"\\\",\\\"cuisine\\\":\\\"\\\",\\\"budgetPerPerson\\\":-1,\\\"radiusKm\\\":-1,\\\"nearby\\\":false,\\\"arrivalTime\\\":\\\"\\\",\\\"preferences\\\":[\\\"适合聊天\\\"],\\\"missingInformation\\\":[] }\"}}]}}]}" );
        when(client.chatCompletion(any(), any(), any(), any())).thenReturn(modelResponse);

        DecisionConstraints constraints = extractor.extract("福州有什么吃的，适合聊天");

        assertTrue(constraints.getPreferences().contains("安静"));
        assertTrue(!constraints.getPreferences().contains("适合聊天"));
        assertEquals(ConstraintSource.DERIVED, constraints.getSourceHints().get("preference:安静"));
    }

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
        assertEquals("", constraints.getTargetCity());
        assertEquals("解放碑", constraints.getTargetArea());
        assertEquals("火锅", constraints.getKeyword());
        assertEquals(Integer.valueOf(100), constraints.getBudgetPerPerson());
        assertTrue(constraints.getPreferences().contains("约会"));
    }

    @Test
    void preservesStructuredProvinceWithoutInferringParentForCity() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ConstraintExtractor extractor = new ConstraintExtractor();
        ReflectionTestUtils.setField(extractor, "aiClient", client);
        ReflectionTestUtils.setField(extractor, "objectMapper", objectMapper);
        Map<String, Object> extracted = new LinkedHashMap<>();
        extracted.put("targetProvince", "福建省"); extracted.put("targetCity", "福州市"); extracted.put("targetArea", "");
        extracted.put("locationIntent", "EXPLICIT_TARGET"); extracted.put("keyword", ""); extracted.put("cuisine", "");
        extracted.put("budgetPerPerson", -1); extracted.put("radiusKm", -1); extracted.put("nearby", false);
        extracted.put("arrivalTime", ""); extracted.put("preferences", List.of()); extracted.put("missingInformation", List.of());
        Map<String, Object> function = Map.of("arguments", objectMapper.writeValueAsString(extracted));
        Map<String, Object> toolCall = Map.of("function", function);
        Map<String, Object> message = Map.of("tool_calls", List.of(toolCall));
        JsonNode modelResponse = objectMapper.valueToTree(Map.of("choices", List.of(Map.of("message", message))));
        when(client.chatCompletion(any(), any(), any(), any())).thenReturn(modelResponse);

        DecisionConstraints constraints = extractor.extract("福建福州有什么好吃的");

        assertEquals("福建省", constraints.getTargetProvince());
        assertEquals("福州市", constraints.getTargetCity());
        assertEquals("EXPLICIT_TARGET", constraints.getLocationIntent());
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
        assertTrue(!constraints.getMissingInformation().contains("administrativeRegion"));
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

    @Test
    void migratesCuisineKeywordToCuisineWhenModelPutsCuisineInKeyword() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ConstraintExtractor extractor = new ConstraintExtractor();
        ReflectionTestUtils.setField(extractor, "aiClient", client);
        ReflectionTestUtils.setField(extractor, "objectMapper", objectMapper);
        JsonNode modelResponse = objectMapper.readTree("{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"targetCity\\\":\\\"\\\",\\\"targetArea\\\":\\\"\\\",\\\"locationIntent\\\":\\\"CURRENT_DEVICE\\\",\\\"keyword\\\":\\\"沙县小吃\\\",\\\"cuisine\\\":\\\"\\\",\\\"budgetPerPerson\\\":-1,\\\"radiusKm\\\":-1,\\\"nearby\\\":true,\\\"arrivalTime\\\":\\\"\\\",\\\"preferences\\\":[],\\\"missingInformation\\\":[]}\"}}]}}]}");
        when(client.chatCompletion(any(), any(), any(), any())).thenReturn(modelResponse);

        DecisionConstraints constraints = extractor.extract("附近有没有沙县小吃");

        assertEquals("小吃", constraints.getCuisine());
        assertEquals("", constraints.getKeyword());
    }

    @Test
    void keepsNamedShopKeywordWhenItIsNotACuisine() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ConstraintExtractor extractor = new ConstraintExtractor();
        ReflectionTestUtils.setField(extractor, "aiClient", client);
        ReflectionTestUtils.setField(extractor, "objectMapper", objectMapper);
        JsonNode modelResponse = objectMapper.readTree("{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"targetCity\\\":\\\"\\\",\\\"targetArea\\\":\\\"\\\",\\\"locationIntent\\\":\\\"CURRENT_DEVICE\\\",\\\"keyword\\\":\\\"闽师东北菜\\\",\\\"cuisine\\\":\\\"\\\",\\\"budgetPerPerson\\\":-1,\\\"radiusKm\\\":-1,\\\"nearby\\\":true,\\\"arrivalTime\\\":\\\"\\\",\\\"preferences\\\":[],\\\"missingInformation\\\":[]}\"}}]}}]}");
        when(client.chatCompletion(any(), any(), any(), any())).thenReturn(modelResponse);

        DecisionConstraints constraints = extractor.extract("去闽师东北菜吃");

        assertEquals("闽师东北菜", constraints.getKeyword());
        assertEquals("", constraints.getCuisine());
    }

    @Test
    void restoresDeterministicNearbyAndRadiusWhenModelOmitsLocationFields() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ConstraintExtractor extractor = new ConstraintExtractor();
        ReflectionTestUtils.setField(extractor, "aiClient", client);
        ReflectionTestUtils.setField(extractor, "objectMapper", objectMapper);
        JsonNode modelResponse = objectMapper.readTree("{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"targetCity\\\":\\\"福州\\\",\\\"targetArea\\\":\\\"\\\",\\\"locationIntent\\\":\\\"EXPLICIT_TARGET\\\",\\\"keyword\\\":\\\"\\\",\\\"cuisine\\\":\\\"火锅\\\",\\\"budgetPerPerson\\\":-1,\\\"budgetDirection\\\":0,\\\"radiusKm\\\":-1,\\\"radiusDirection\\\":0,\\\"nearby\\\":false,\\\"arrivalTime\\\":\\\"\\\",\\\"preferences\\\":[],\\\"missingInformation\\\":[],\\\"clearedFields\\\":[],\\\"removedPreferences\\\":[]}\"}}]}}]}" );
        when(client.chatCompletion(any(), any(), any(), any())).thenReturn(modelResponse);

        DecisionConstraints constraints = extractor.extract("在福州附近3公里内找火锅");

        assertEquals(true, constraints.getNearby());
        assertEquals(3D, constraints.getRadiusKm());
    }

    @Test
    void extractsStructuredPreferenceRemovalWhenUserAcceptsQueueing() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ConstraintExtractor extractor = new ConstraintExtractor();
        ReflectionTestUtils.setField(extractor, "aiClient", client);
        ReflectionTestUtils.setField(extractor, "objectMapper", objectMapper);
        JsonNode modelResponse = objectMapper.readTree("{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"targetCity\\\":\\\"\\\",\\\"targetArea\\\":\\\"\\\",\\\"locationIntent\\\":\\\"UNSPECIFIED\\\",\\\"keyword\\\":\\\"\\\",\\\"cuisine\\\":\\\"火锅\\\",\\\"budgetPerPerson\\\":-1,\\\"budgetDirection\\\":0,\\\"radiusKm\\\":-1,\\\"radiusDirection\\\":0,\\\"nearby\\\":false,\\\"arrivalTime\\\":\\\"\\\",\\\"preferences\\\":[],\\\"missingInformation\\\":[],\\\"clearedFields\\\":[],\\\"removedPreferences\\\":[]}\"}}]}}]}" );
        when(client.chatCompletion(any(), any(), any(), any())).thenReturn(modelResponse);

        DecisionConstraints constraints = extractor.extract("排队也行，改找火锅");

        assertTrue(constraints.getRemovedPreferences().contains("不排队"));
    }

    @Test
    void doesNotMigrateKeywordWhenCuisineAlreadyExtracted() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ConstraintExtractor extractor = new ConstraintExtractor();
        ReflectionTestUtils.setField(extractor, "aiClient", client);
        ReflectionTestUtils.setField(extractor, "objectMapper", objectMapper);
        JsonNode modelResponse = objectMapper.readTree("{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"targetCity\\\":\\\"\\\",\\\"targetArea\\\":\\\"\\\",\\\"locationIntent\\\":\\\"CURRENT_DEVICE\\\",\\\"keyword\\\":\\\"沙县\\\",\\\"cuisine\\\":\\\"日料\\\",\\\"budgetPerPerson\\\":-1,\\\"radiusKm\\\":-1,\\\"nearby\\\":true,\\\"arrivalTime\\\":\\\"\\\",\\\"preferences\\\":[],\\\"missingInformation\\\":[]}\"}}]}}]}");
        when(client.chatCompletion(any(), any(), any(), any())).thenReturn(modelResponse);

        DecisionConstraints constraints = extractor.extract("日料店，附近有沙县吗");

        assertEquals("日料", constraints.getCuisine());
        assertEquals("沙县", constraints.getKeyword());
    }

    @Test
    void extractsDistrictAsAdministrativeScopeAndKeepsLandmarkAsArea() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ConstraintExtractor extractor = new ConstraintExtractor();
        ReflectionTestUtils.setField(extractor, "aiClient", client);
        ReflectionTestUtils.setField(extractor, "objectMapper", objectMapper);
        JsonNode modelResponse = objectMapper.readTree("{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"targetProvince\\\":\\\"福建省\\\",\\\"targetCity\\\":\\\"福州市\\\",\\\"targetDistrict\\\":\\\"闽侯县\\\",\\\"targetArea\\\":\\\"\\\",\\\"locationIntent\\\":\\\"EXPLICIT_TARGET\\\",\\\"keyword\\\":\\\"\\\",\\\"cuisine\\\":\\\"\\\",\\\"budgetPerPerson\\\":-1,\\\"radiusKm\\\":-1,\\\"nearby\\\":false,\\\"arrivalTime\\\":\\\"\\\",\\\"preferences\\\":[],\\\"missingInformation\\\":[],\\\"clearedFields\\\":[],\\\"removedPreferences\\\":[] }\"}}]}}]}" );
        when(client.chatCompletion(any(), any(), any(), any())).thenReturn(modelResponse);

        DecisionConstraints constraints = extractor.extract("闽侯县有什么吃的");

        assertEquals("", constraints.getTargetDistrict());
        assertTrue(constraints.getMissingInformation().contains("administrativeRegion"));
        assertEquals("", constraints.getTargetArea());
    }

    @Test
    void preservesAdministrativeRegionWhenModelReturnsEmptyToolCalls() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ConstraintExtractor extractor = new ConstraintExtractor();
        ReflectionTestUtils.setField(extractor, "aiClient", client);
        ReflectionTestUtils.setField(extractor, "objectMapper", objectMapper);
        when(client.chatCompletion(any(), any(), any(), any())).thenReturn(
                objectMapper.readTree("{\"choices\":[{\"message\":{\"tool_calls\":[]}}]}"));

        DecisionConstraints constraints = extractor.extract("福建省有什么吃的");

        assertEquals("福建省", constraints.getTargetProvince());
        assertEquals("EXPLICIT_TARGET", constraints.getLocationIntent());
    }

    @Test
    void resolvesDistrictShortNameUsingParentContextWhenModelFails() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ConstraintExtractor extractor = new ConstraintExtractor();
        ReflectionTestUtils.setField(extractor, "aiClient", client);
        ReflectionTestUtils.setField(extractor, "objectMapper", objectMapper);
        when(client.chatCompletion(any(), any(), any(), any())).thenReturn(
                objectMapper.readTree("{\"choices\":[{\"message\":{\"tool_calls\":[]}}]}"));
        DecisionConstraints context = new DecisionConstraints();
        context.setTargetCity("福州市");

        DecisionConstraints constraints = extractor.extract("鼓楼呢？", context);

        assertEquals("鼓楼区", constraints.getTargetDistrict());
        assertEquals("福州市", constraints.getTargetCity());
    }

    @Test
    void doesNotGroundCityHintFromPoiPrefix() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ConstraintExtractor extractor = new ConstraintExtractor();
        ReflectionTestUtils.setField(extractor, "aiClient", client);
        ReflectionTestUtils.setField(extractor, "objectMapper", objectMapper);
        JsonNode modelResponse = modelResponse(objectMapper, "{\"targetCity\":\"福州市\",\"targetArea\":\"福州大学\",\"locationIntent\":\"EXPLICIT_TARGET\",\"keyword\":\"\",\"cuisine\":\"\",\"budgetPerPerson\":-1,\"radiusKm\":-1,\"nearby\":false,\"arrivalTime\":\"\",\"preferences\":[],\"missingInformation\":[]}");
        when(client.chatCompletion(any(), any(), any(), any())).thenReturn(modelResponse);

        DecisionConstraints constraints = extractor.extract("福州大学附近有什么吃的");

        assertEquals("", constraints.getTargetCity());
        assertEquals("福州大学", constraints.getTargetArea());
    }

    @Test
    void keepsExplicitCityAndPoiTogether() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ConstraintExtractor extractor = new ConstraintExtractor();
        ReflectionTestUtils.setField(extractor, "aiClient", client);
        ReflectionTestUtils.setField(extractor, "objectMapper", objectMapper);
        JsonNode modelResponse = modelResponse(objectMapper, "{\"targetCity\":\"福州市\",\"targetArea\":\"福州大学\",\"locationIntent\":\"EXPLICIT_TARGET\",\"keyword\":\"\",\"cuisine\":\"\",\"budgetPerPerson\":-1,\"radiusKm\":-1,\"nearby\":false,\"arrivalTime\":\"\",\"preferences\":[],\"missingInformation\":[]}");
        when(client.chatCompletion(any(), any(), any(), any())).thenReturn(modelResponse);

        DecisionConstraints constraints = extractor.extract("福州市福州大学附近有什么吃的");

        assertEquals("福州市", constraints.getTargetCity());
        assertEquals("福州大学", constraints.getTargetArea());
    }

    @Test
    void doesNotGroundDistrictHintFromAnotherPoiPrefix() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ConstraintExtractor extractor = new ConstraintExtractor();
        ReflectionTestUtils.setField(extractor, "aiClient", client);
        ReflectionTestUtils.setField(extractor, "objectMapper", objectMapper);
        JsonNode modelResponse = modelResponse(objectMapper, "{\"targetDistrict\":\"仓山区\",\"targetArea\":\"仓山公园\",\"locationIntent\":\"EXPLICIT_TARGET\",\"keyword\":\"\",\"cuisine\":\"\",\"budgetPerPerson\":-1,\"radiusKm\":-1,\"nearby\":false,\"arrivalTime\":\"\",\"preferences\":[],\"missingInformation\":[]}");
        when(client.chatCompletion(any(), any(), any(), any())).thenReturn(modelResponse);

        DecisionConstraints constraints = extractor.extract("仓山公园附近有什么吃的");

        assertEquals("", constraints.getTargetDistrict());
        assertEquals("仓山公园", constraints.getTargetArea());
    }
    private JsonNode modelResponse(ObjectMapper objectMapper, String arguments) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("arguments", arguments);
        Map<String, Object> toolCall = Map.of("function", function);
        Map<String, Object> message = Map.of("tool_calls", List.of(toolCall));
        return objectMapper.valueToTree(Map.of("choices", List.of(Map.of("message", message))));
    }
}
