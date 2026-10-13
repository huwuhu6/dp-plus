package com.hmdp.ai.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.v2.runtime.SemanticInterpreter;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class V2ModelCallEvidenceTest {
    @Test void actualInterpreterSuppliesOnlyCurrentMessageAndProductionContract() throws Exception {
        ObjectMapper json=new ObjectMapper();SemanticInterpreter interpreter=new SemanticInterpreter();
        OpenAiCompatibleClient client=mock(OpenAiCompatibleClient.class);V2ModelCallEvidence observer=new V2ModelCallEvidence(json);
        var response=json.readTree("""
            {"choices":[{"message":{"tool_calls":[{"function":{"arguments":"{\\"taskDirective\\":\\"CONTINUE\\",\\"criteria\\":{\\"cuisine\\":\\"寿司\\"},\\"requests\\":[{\\"requestId\\":\\"r1\\",\\"type\\":\\"RECOMMENDATION\\"}]}"}}]}}],"usage":{"prompt_tokens":10,"completion_tokens":20}}
            """);
        when(client.chatCompletion(anyList(),anyList(),anyMap(),eq("V2_SEMANTIC_INTERPRETATION")))
                .thenAnswer(i->observer.observe(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),()->response));
        ReflectionTestUtils.setField(interpreter,"aiClient",client);ReflectionTestUtils.setField(interpreter,"objectMapper",json);
        assertEquals("CONTINUE",interpreter.interpret("换成寿司吧").taskDirective().name());
        assertEquals(1,observer.calls.size());
        var input=json.valueToTree(observer.calls.getFirst());
        assertEquals(2,input.path("messages").size());assertEquals("换成寿司吧",input.path("messages").get(1).path("content").asText());
        var criteria=input.path("tools").get(0).path("function").path("parameters").path("properties").path("criteria").path("properties");
        assertFalse(criteria.has("diningTime"));assertFalse(criteria.has("semanticPreferences"));
        assertEquals(10,input.path("usage").path("prompt_tokens").asInt());
        assertEquals("VALID",interpreter.takeEvaluationAttempts().getFirst().get("validationResult"));
    }

    @Test void unavailableTransportIsNotSemanticFailureOrSuccessfulObservation() {
        var observer=new V2ModelCallEvidence(new ObjectMapper());
        var failure=new org.springframework.web.client.ResourceAccessException("secret-in-message");
        assertSame(failure,assertThrows(RuntimeException.class,()->observer.observe(List.of(Map.of("role","user","content","原话")),List.of(),Map.of(),"V2",()->{throw failure;})));
        assertEquals(false,observer.calls.getFirst().get("success"));assertFalse(observer.calls.getFirst().containsKey("response"));
        assertFalse(observer.calls.toString().contains("secret-in-message"));
    }
}
