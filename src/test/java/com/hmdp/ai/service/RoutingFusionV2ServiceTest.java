package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.RoutingFusionV2Result;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutingFusionV2ServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesCompactRoutingAndCriteriaInOneResult() throws Exception {
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        StructuredUnderstandingService service = service(client);
        when(client.chatRoutingFusionCompletion(any(), any(), any(), any()))
                .thenReturn(response("{\"version\":\"v2\",\"acts\":["
                        + "{\"type\":\"MUTATE_CRITERIA\",\"evidenceText\":\"便宜一点\"}],"
                        + "\"criteriaDelta\":[{\"field\":\"BUDGET_PER_PERSON\",\"operation\":\"DECREASE\","
                        + "\"rawValue\":\"便宜一点\",\"evidenceText\":\"便宜一点\"}],"
                        + "\"ambiguities\":[]}"));

        RoutingFusionV2Result result = service.understandRoutingFusion("便宜一点", Collections.emptyList(), Collections.emptyMap());

        assertTrue(result.isValid());
        assertTrue(result.isCriteriaReusable());
        assertEquals("v2", result.getIr().getVersion());
        assertEquals(1, result.getIr().getCriteriaDelta().size());
        verify(client).chatRoutingFusionCompletion(any(), any(), any(), any());
    }

    @Test
    void evidenceOffsetFieldsAreRejectedAndRepeatedTextIsAmbiguous() throws Exception {
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        StructuredUnderstandingService service = service(client);
        when(client.chatRoutingFusionCompletion(any(), any(), any(), any()))
                .thenReturn(response("{\"version\":\"v2\",\"acts\":["
                        + "{\"type\":\"MUTATE_CRITERIA\",\"evidenceText\":\"火锅\",\"start\":0,\"end\":2}],"
                        + "\"criteriaDelta\":[],\"ambiguities\":[]}"));

        RoutingFusionV2Result forbidden = service.understandRoutingFusion("火锅火锅", Collections.emptyList(), Collections.emptyMap());
        assertTrue(forbidden.isFallback());
        assertFalse(forbidden.isValid());

        when(client.chatRoutingFusionCompletion(any(), any(), any(), any()))
                .thenReturn(response("{\"version\":\"v2\",\"acts\":["
                        + "{\"type\":\"MUTATE_CRITERIA\",\"evidenceText\":\"火锅\"}],"
                        + "\"criteriaDelta\":[],\"ambiguities\":[]}"));
        RoutingFusionV2Result ambiguous = service.understandRoutingFusion("火锅火锅", Collections.emptyList(), Collections.emptyMap());
        assertTrue(ambiguous.isFallback());
        assertTrue(ambiguous.getValidationErrors().contains("acts[0].evidenceText"));

        when(client.chatRoutingFusionCompletion(any(), any(), any(), any()))
                .thenReturn(response("{\"version\":\"v2\",\"acts\":["
                        + "{\"type\":\"REQUEST_RECOMMENDATION\",\"evidenceText\":\"火锅\"}],"
                        + "\"criteriaDelta\":[],\"references\":[],\"ambiguities\":[]}"));
        RoutingFusionV2Result forbiddenReference = service.understandRoutingFusion(
                "火锅", Collections.emptyList(), Collections.emptyMap());
        assertTrue(forbiddenReference.isFallback());
    }

    @Test
    @SuppressWarnings("unchecked")
    void compactSchemaDoesNotExposeFullIrFields() {
        StructuredUnderstandingService service = service(mock(OpenAiCompatibleClient.class));
        Map<String, Object> schema = service.routingFusionSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("acts"));
        assertTrue(properties.containsKey("criteriaDelta"));
        assertTrue(properties.containsKey("locationExpression"));
        assertFalse(properties.containsKey("references"));
        assertFalse(properties.containsKey("shopFactQueries"));
        assertFalse(properties.containsKey("decisionContextQuery"));
    }

    @Test
    void keepsRawLocationExpressionWithoutCanonicalizingIt() throws Exception {
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        StructuredUnderstandingService service = service(client);
        when(client.chatRoutingFusionCompletion(any(), any(), any(), any()))
                .thenReturn(response("{\"version\":\"v2\",\"acts\":["
                        + "{\"type\":\"REQUEST_RECOMMENDATION\",\"evidenceText\":\"厦门火锅\"}],"
                        + "\"criteriaDelta\":[],\"locationExpression\":{\"rawText\":\"厦门\","
                        + "\"reset\":false,\"evidenceText\":\"厦门\"},\"ambiguities\":[]}"));

        RoutingFusionV2Result result = service.understandRoutingFusion("厦门火锅", Collections.emptyList(), Collections.emptyMap());

        assertTrue(result.isValid());
        assertEquals("厦门", result.getIr().getLocationExpression().getRawText());
        assertEquals("厦门", result.getIr().getLocationExpression().getEvidenceText());
    }

    @Test
    void malformedNullCollectionsKeepPreciseValidationErrors() throws Exception {
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        StructuredUnderstandingService service = service(client);
        when(client.chatRoutingFusionCompletion(any(), any(), any(), any()))
                .thenReturn(response("{\"version\":\"v2\",\"acts\":null,"
                        + "\"criteriaDelta\":null,\"ambiguities\":null}"));

        RoutingFusionV2Result result = service.understandRoutingFusion(
                "随便聊聊", Collections.emptyList(), Collections.emptyMap());

        assertFalse(result.isValid());
        assertTrue(result.getValidationErrors().contains("acts"));
        assertTrue(result.getValidationErrors().contains("criteriaDelta"));
        assertTrue(result.getValidationErrors().contains("ambiguities"));
    }

    private StructuredUnderstandingService service(OpenAiCompatibleClient client) {
        StructuredUnderstandingService service = new StructuredUnderstandingService();
        AiProperties properties = new AiProperties();
        AiProperties.StructuredUnderstandingProperties structured = new AiProperties.StructuredUnderstandingProperties();
        structured.setMode("shadow");
        properties.setStructuredUnderstanding(structured);
        ReflectionTestUtils.setField(service, "aiClient", client);
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(service, "aiProperties", properties);
        ReflectionTestUtils.setField(service, "evidenceGrounder", new EvidenceGrounder());
        return service;
    }

    private JsonNode response(String arguments) throws Exception {
        return objectMapper.readTree("{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":"
                + objectMapper.writeValueAsString(arguments) + "}}]}}]}");
    }
}
