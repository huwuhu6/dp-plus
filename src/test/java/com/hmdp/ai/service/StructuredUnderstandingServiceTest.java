package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.StructuredUnderstandingResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StructuredUnderstandingServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesCompoundTurnAndValidatesEvidenceWithoutEntityIds() throws Exception {
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        StructuredUnderstandingService service = service(client, "shadow");
        when(client.chatCompletion(any(), any(), any(), eq("STRUCTURED_UNDERSTANDING"), any()))
                .thenReturn(response("{\"version\":\"v1\",\"acts\":["
                        + "{\"type\":\"MUTATE_CRITERIA\",\"evidence\":{\"text\":\"太贵了\",\"start\":3,\"end\":6},\"details\":[]},"
                        + "{\"type\":\"ASK_SHOP_FACT\",\"evidence\":{\"text\":\"有插座吗\",\"start\":10,\"end\":14},\"details\":[]}],"
                        + "\"references\":[],\"criteriaDelta\":[],\"locationExpression\":null,"
                        + "\"decisionContextQuery\":null,\"shopFactQueries\":[],\"ambiguities\":[]}"));

        StructuredUnderstandingResult result = service.understand("第一家太贵了，第二家有插座吗？",
                Collections.emptyList(), Collections.emptyMap());

        assertTrue(result.isValid());
        assertFalse(result.isFallback());
        assertEquals(2, result.getIr().getActs().size());
        verify(client).chatCompletion(any(), any(), any(), eq("STRUCTURED_UNDERSTANDING"), any());
    }

    @Test
    void invalidEvidenceFailsClosedWithoutRepairCall() throws Exception {
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        StructuredUnderstandingService service = service(client, "active");
        when(client.chatCompletion(any(), any(), any(), eq("STRUCTURED_UNDERSTANDING"), any()))
                .thenReturn(response("{\"version\":\"v1\",\"acts\":[{\"type\":\"MUTATE_CRITERIA\","
                        + "\"evidence\":{\"text\":\"第二家\",\"start\":0,\"end\":3},\"details\":[]}],"
                        + "\"references\":[],\"criteriaDelta\":[],\"locationExpression\":null,"
                        + "\"decisionContextQuery\":null,\"shopFactQueries\":[],\"ambiguities\":[]}"));

        StructuredUnderstandingResult result = service.understand("第一家太贵了",
                Collections.emptyList(), Collections.emptyMap());

        assertFalse(result.isValid());
        assertTrue(result.isFallback());
        assertEquals("INVALID_EVIDENCE_OR_SCHEMA", result.getFailureReason());
        verify(client).chatCompletion(any(), any(), any(), eq("STRUCTURED_UNDERSTANDING"), any());
    }

    @Test
    void modeOffDoesNotCallStructuredModel() {
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        StructuredUnderstandingService service = service(client, "off");

        StructuredUnderstandingResult result = service.understand("推荐火锅", Collections.emptyList(), Collections.emptyMap());

        assertFalse(result.isValid());
        assertFalse(result.isFallback());
        assertEquals("MODE_OFF", result.getFailureReason());
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void emptyOptionalQueryObjectIsTreatedAsAbsent() throws Exception {
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        StructuredUnderstandingService service = service(client, "active");
        when(client.chatCompletion(any(), any(), any(), eq("STRUCTURED_UNDERSTANDING"), any()))
                .thenReturn(response("{\"version\":\"v1\",\"acts\":["
                        + "{\"type\":\"REQUEST_RECOMMENDATION\",\"evidence\":{\"text\":\"推荐火锅\",\"start\":0,\"end\":4},\"details\":[]}],"
                        + "\"references\":[],\"criteriaDelta\":[],\"locationExpression\":{\"rawText\":\"\",\"reset\":false,\"evidence\":{\"text\":\"\",\"start\":0,\"end\":0}},"
                        + "\"decisionContextQuery\":{\"referenceId\":\"\",\"constraintKey\":\"\"},\"shopFactQueries\":[],\"ambiguities\":[]}"));

        StructuredUnderstandingResult result = service.understand("推荐火锅", Collections.emptyList(), Collections.emptyMap());

        assertTrue(result.isValid());
        assertEquals(null, result.getIr().getLocationExpression());
        assertEquals(null, result.getIr().getDecisionContextQuery());
    }

    private StructuredUnderstandingService service(OpenAiCompatibleClient client, String mode) {
        StructuredUnderstandingService service = new StructuredUnderstandingService();
        AiProperties properties = new AiProperties();
        AiProperties.StructuredUnderstandingProperties structured = new AiProperties.StructuredUnderstandingProperties();
        structured.setMode(mode);
        properties.setStructuredUnderstanding(structured);
        ReflectionTestUtils.setField(service, "aiClient", client);
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(service, "aiProperties", properties);
        return service;
    }

    private JsonNode response(String arguments) throws Exception {
        return objectMapper.readTree("{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":"
                + objectMapper.writeValueAsString(arguments) + "}}]}}]}");
    }
}
