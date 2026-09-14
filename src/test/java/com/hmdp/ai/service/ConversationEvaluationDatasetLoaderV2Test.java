package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.entity.AiConversationEvaluationCase;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class ConversationEvaluationDatasetLoaderV2Test {
    @Test
    void defaultDatasetCarriesV2OutcomeAssertions() {
        ConversationEvaluationDatasetLoader loader = new ConversationEvaluationDatasetLoader();
        ReflectionTestUtils.setField(loader, "objectMapper", new ObjectMapper());
        AiConversationEvaluationCase locationCase = loader.loadCases("conversation-v2-runtime-v1").stream()
                .filter(item -> item.getCaseCode().equals("V2_DEVICE_ANCHOR_AND_ACTIVE_TASK")).findFirst().orElseThrow();
        assertTrue(locationCase.getExpectedV2OutcomesJson().contains("searchAnchor.source"));
        AiConversationEvaluationCase relativeCase = loader.loadCases("conversation-v2-runtime-v1").stream()
                .filter(item -> item.getCaseCode().equals("V2_RELATIVE_PRICE_IS_NOT_BUDGET")).findFirst().orElseThrow();
        assertTrue(relativeCase.getExpectedV2OutcomesJson().contains("relativePreferences"));
        assertTrue(relativeCase.getExpectedV2OutcomesJson().contains("\"absent\":true"));
        assertFalse(relativeCase.getExpectedV2OutcomesJson().contains("\"null\":true"));
    }
}
