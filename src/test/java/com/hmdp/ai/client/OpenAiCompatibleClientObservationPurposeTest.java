package com.hmdp.ai.client;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAiCompatibleClientObservationPurposeTest {
    @Test
    void recordsSemanticPurposesSeparately() {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient();
        assertEquals("REWRITE", ReflectionTestUtils.invokeMethod(client, "observationPurpose", "REWRITE"));
        assertEquals("ROUTING", ReflectionTestUtils.invokeMethod(client, "observationPurpose", "CHAT_ROUTING"));
        assertEquals("EXTRACTION", ReflectionTestUtils.invokeMethod(client, "observationPurpose", "CONSTRAINT_EXTRACTION"));
        assertEquals("STRUCTURED_UNDERSTANDING", ReflectionTestUtils.invokeMethod(client, "observationPurpose", "STRUCTURED_UNDERSTANDING"));
        assertEquals("OTHER", ReflectionTestUtils.invokeMethod(client, "observationPurpose", "NARRATIVE_GENERATION"));
    }
}
