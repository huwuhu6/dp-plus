package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.entity.AiConversationEvaluationCase;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class ConversationEvaluationDatasetLoaderV2Test {
    @Test
    void legacyRuntimeDatasetStillLoadsWithValidContainerAssertions() {
        ConversationEvaluationDatasetLoader loader = new ConversationEvaluationDatasetLoader();
        ReflectionTestUtils.setField(loader, "objectMapper", new ObjectMapper());
        var cases = loader.loadCases("conversation-v2-runtime-v1");
        assertEquals(3, cases.size());
        AiConversationEvaluationCase location = cases.stream()
                .filter(item -> item.getCaseCode().equals("V2_DEVICE_ANCHOR_AND_ACTIVE_TASK")).findFirst().orElseThrow();
        assertTrue(location.getExpectedV2OutcomesJson().contains("searchAnchor.source"));
        AiConversationEvaluationCase relative = cases.stream()
                .filter(item -> item.getCaseCode().equals("V2_RELATIVE_PRICE_IS_NOT_BUDGET")).findFirst().orElseThrow();
        assertTrue(relative.getExpectedV2OutcomesJson().contains("criteria.budget"));
        assertTrue(relative.getExpectedV2OutcomesJson().contains("relativePreferences"));
        assertTrue(relative.getExpectedV2OutcomesJson().contains("\"absent\":true"));
        assertFalse(relative.getExpectedV2OutcomesJson().contains("\"null\":true"));
    }

    @Test
    void auditedMainDatasetIsLoadableAndContainsScoredIdentityAndVerificationContracts() {
        ConversationEvaluationDatasetLoader loader = new ConversationEvaluationDatasetLoader();
        ReflectionTestUtils.setField(loader, "objectMapper", new ObjectMapper());

        var cases = loader.loadCases("conversation-v2-main-v2");

        assertEquals(24, cases.size());
        assertTrue(cases.stream().noneMatch(item -> item.getExpectedFinalStatus() != null && item.getExpectedFinalStatus().contains("|")));
        AiConversationEvaluationCase ordinal = cases.stream().filter(item -> item.getCaseCode().equals("MAIN_V2_REFERENCE_FIRST_IDENTITY"))
                .findFirst().orElseThrow();
        assertTrue(ordinal.getExpectedRelationsJson().contains("groundedOrdinal"));
        AiConversationEvaluationCase verification = cases.stream().filter(item -> item.getCaseCode().equals("MAIN_V2_VERIFICATION_UNSUPPORTED"))
                .findFirst().orElseThrow();
        assertTrue(verification.getExpectedV2OutcomesJson().contains("verificationStatus"));
    }

    @Test void semanticMainIsLoadedFromJsonlAndMissingV2CannotFallBackToMysql() {
        ConversationEvaluationDatasetLoader loader = new ConversationEvaluationDatasetLoader();
        ReflectionTestUtils.setField(loader, "objectMapper", new ObjectMapper());
        var caseMapper = org.mockito.Mockito.mock(com.hmdp.ai.mapper.AiConversationEvaluationCaseMapper.class);
        ReflectionTestUtils.setField(loader, "caseMapper", caseMapper);
        var cases = loader.loadCases("conversation-v2-semantic-main-v1");
        assertFalse(cases.isEmpty());
        assertTrue(cases.stream().allMatch(item -> "conversation-v2-semantic-main-v1".equals(item.getDatasetVersion())));
        assertThrows(IllegalStateException.class, () -> loader.loadCases("conversation-v2-missing-v1"));
        org.mockito.Mockito.verifyNoInteractions(caseMapper);
    }
}
