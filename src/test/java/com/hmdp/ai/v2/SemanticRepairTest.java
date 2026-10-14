package com.hmdp.ai.v2;

import com.fasterxml.jackson.databind.*;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.v2.runtime.*;
import com.hmdp.ai.v2.semantic.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SemanticRepairTest {
    final ObjectMapper json=new ObjectMapper();
    final OpenAiCompatibleClient client=mock(OpenAiCompatibleClient.class);
    final SemanticInterpreter parser=new SemanticInterpreter();
    SemanticRepairTest() {ReflectionTestUtils.setField(parser,"aiClient",client);ReflectionTestUtils.setField(parser,"objectMapper",json);}
    JsonNode response(String raw) {return json.valueToTree(Map.of("choices",List.of(Map.of("message",Map.of("tool_calls",List.of(Map.of("function",Map.of("arguments",raw))))))));}
    void replies(String first,String second) {when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(first),response(second));}
    String request="\"requests\":[{\"requestId\":\"r1\",\"type\":\"RECOMMENDATION\",\"target\":{},\"fact\":\"DETAIL\"}]";
    String first="{\"criteria\":{\"cuisine\":\"面\",\"partySize\":1},"+request+"}";
    DiningCriteriaPatch patch(TurnSemantics turn) {return assertInstanceOf(RequirementChange.CriteriaPatch.class,turn.requirementChanges().getFirst()).patch();}

    @Test void missingTimeRepairCannotSilentlyDeleteSourceCheckedCuisineAndParty() {
        replies(first,"{\"criteria\":{\"arrivalTime\":\"今晚\"},\"requests\":[]}");
        var result=patch(parser.interpret("今晚一个人吃碗面"));
        assertEquals("面",result.cuisine().include());assertEquals(1,result.partySize());assertEquals("今晚",result.diningTime().arrivalTime());
        var traces=parser.takeEvaluationAttempts();assertEquals("VALID",traces.getLast().get("validationResult"));
        assertTrue(traces.getLast().get("fieldDecisions").toString().contains("criteria.cuisine=PRESERVED_FIRST"));
        var inputs=org.mockito.ArgumentCaptor.forClass(List.class);verify(client,times(2)).chatCompletion(inputs.capture(),anyList(),anyMap(),anyString());
        String sent=inputs.getAllValues().getLast().toString();assertTrue(sent.contains("firstStructuredOutput"));assertTrue(sent.contains("failedDimensions"));assertTrue(sent.contains("criteria.arrivalTime"));assertTrue(sent.contains("sourceCheckedFieldsToPreserve"));
    }
    @Test void wrongFirstPlaceIsCorrectableWhileValidCuisineSurvives() {
        replies("{\"criteria\":{\"cuisine\":\"日料\",\"poi\":\"虚构地点\",\"partySize\":\"bad\"},"+request+"}",
                "{\"criteria\":{\"poi\":\"大学城\"},"+request+"}");
        var result=patch(parser.interpret("大学城附近找日料"));
        assertEquals("日料",result.cuisine().include());assertEquals("大学城",result.location().poi());assertNull(result.partySize());
    }
    @Test void repairExtraBudgetIsRejectedRatherThanBecomingUserCondition() {
        replies(first,"{\"criteria\":{\"arrivalTime\":\"今晚\",\"budgetHard\":80},"+request+"}");
        var error=assertThrows(SemanticInterpretationException.class,()->parser.interpret("今晚一个人吃碗面"));
        assertTrue(error.getCause().getMessage().contains("criteria.budgetHard"));
    }
    @Test void repairStillMissingTimeFailsFullRevalidation() {
        replies(first,"{\"criteria\":{},"+request+"}");
        var error=assertThrows(SemanticInterpretationException.class,()->parser.interpret("今晚一个人吃碗面"));
        assertEquals("DINING_DETAILS_REQUIRED",error.getCause().getMessage());
    }
    @Test void protectedPreferenceCannotBeUpgradedToMust() {
        replies("{\"criteria\":{\"cuisine\":\"日料\",\"preferences\":[{\"criterion\":\"安静\",\"importance\":\"PREFER\"}]},"+request+"}",
                "{\"criteria\":{\"arrivalTime\":\"今晚\",\"preferences\":[{\"criterion\":\"安静\",\"importance\":\"MUST\"}]},"+request+"}");
        assertThrows(SemanticInterpretationException.class,()->parser.interpret("今晚找日料，环境要安静一点"));
    }
    @Test void repairCannotInventSelectionFromPositiveAppraisal() {
        replies("{\"criteria\":{\"cuisine\":\"日料\"},\"feedback\":[{\"target\":{\"ordinal\":2},\"kind\":\"POSITIVE\",\"aspect\":\"UNSPECIFIED\"}],"+request+"}",
                "{\"criteria\":{\"arrivalTime\":\"今晚\"},\"requests\":[{\"requestId\":\"r1\",\"type\":\"SELECT\",\"target\":{\"ordinal\":2}}]}");
        assertThrows(SemanticInterpretationException.class,()->parser.interpret("第二家不错，今晚再找日料"));
    }
    @Test void illegalDirectiveEvidenceCanBeCorrected() {
        replies("{\"taskDirective\":\"START_NEW\",\"taskDirectiveEvidence\":\"NONE\",\"criteria\":{\"cuisine\":\"日料\"},"+request+"}",
                "{\"taskDirective\":\"START_NEW\",\"taskDirectiveEvidence\":\"EXPLICIT_NEW_TASK\",\"criteria\":{},"+request+"}");
        var result=parser.interpret("新开一个任务找日料");assertEquals(TaskDirective.START_NEW,result.taskDirective());assertEquals("日料",patch(result).cuisine().include());
    }
}
