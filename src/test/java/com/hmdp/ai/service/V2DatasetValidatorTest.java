package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.entity.AiConversationEvaluationCase;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class V2DatasetValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void acceptsValidCase() { assertDoesNotThrow(() -> validate(caseOf("A"))); }
    @Test void rejectsDuplicateCode() { assertThrows(IllegalArgumentException.class, () -> validate(caseOf("A"), caseOf("A"))); }
    @Test void rejectsInactiveAndEmptyTurns() { AiConversationEvaluationCase inactive = caseOf("A"); inactive.setActive(false); assertThrows(IllegalArgumentException.class, () -> validate(inactive)); AiConversationEvaluationCase empty = caseOf("B"); empty.setTurnsJson("[]"); assertThrows(IllegalArgumentException.class, () -> validate(empty)); }
    @Test void rejectsAmbiguousStatusAndInvalidTurn() { AiConversationEvaluationCase ambiguous = caseOf("A"); ambiguous.setExpectedFinalStatus("COMPLETED|CLARIFYING"); assertThrows(IllegalArgumentException.class, () -> validate(ambiguous)); AiConversationEvaluationCase turn = caseOf("B"); turn.setExpectedV2OutcomesJson("[{\"turn\":2,\"assertions\":{\"decisionStatus\":\"COMPLETED\"}}]"); assertThrows(IllegalArgumentException.class, () -> validate(turn)); }
    @Test void rejectsUnknownOrExtraOperatorAndPath() { AiConversationEvaluationCase operator = caseOf("A"); operator.setExpectedV2OutcomesJson("[{\"turn\":1,\"assertions\":{\"decisionStatus\":{\"unknown\":true}}}]"); assertThrows(IllegalArgumentException.class, () -> validate(operator)); AiConversationEvaluationCase extra = caseOf("B"); extra.setExpectedV2OutcomesJson("[{\"turn\":1,\"assertions\":{\"criteria.budget.hardMax\":{\"notSet\":true,\"unknown\":123}}}]"); assertThrows(IllegalArgumentException.class, () -> validate(extra)); AiConversationEvaluationCase typo = caseOf("C"); typo.setExpectedV2OutcomesJson("[{\"turn\":1,\"assertions\":{\"criteria.bduget.hardMax\":{\"notSet\":true}}}]"); assertThrows(IllegalArgumentException.class, () -> validate(typo)); }
    @Test void rejectsDatasetVersionMismatch() { AiConversationEvaluationCase item = caseOf("A"); item.setDatasetVersion("other-v2"); assertThrows(IllegalArgumentException.class, () -> validate(item)); }
    private void validate(AiConversationEvaluationCase... cases) { V2DatasetValidator.validate("conversation-v2-main-v2", List.of(cases), mapper); }
    private AiConversationEvaluationCase caseOf(String code) { AiConversationEvaluationCase item = new AiConversationEvaluationCase(); item.setCaseCode(code); item.setDatasetVersion("conversation-v2-main-v2"); item.setActive(true); item.setTurnsJson("[{\"message\":\"x\"}]"); item.setExpectedFinalStatus("COMPLETED"); item.setExpectedV2OutcomesJson("[{\"turn\":1,\"assertions\":{\"criteria.budget.hardMax\":{\"notSet\":true}}}]"); return item; }
}
