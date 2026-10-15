package com.hmdp.ai.evaluation;

import com.hmdp.ai.util.CuisineCanonicalizer;
import com.hmdp.ai.v2.runtime.SemanticInterpretationException;
import com.hmdp.ai.v2.runtime.SemanticInterpreter;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.fasterxml.jackson.databind.*;
import org.springframework.test.util.ReflectionTestUtils;
import com.hmdp.ai.v2.semantic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真实parser/来源验证→既有PRE/Grounding/reducer→执行/POST，不伪造验证结论。 */
class FoodCategoryCoverageTest {
    final ObjectMapper json=new ObjectMapper();final OpenAiCompatibleClient client=mock(OpenAiCompatibleClient.class);
    final SemanticInterpreter interpreter=new SemanticInterpreter();
    FoodCategoryCoverageTest(){ReflectionTestUtils.setField(interpreter,"objectMapper",json);ReflectionTestUtils.setField(interpreter,"aiClient",client);}
    JsonNode response(String args){return json.valueToTree(Map.of("choices",List.of(Map.of("message",Map.of("tool_calls",List.of(Map.of("function",Map.of("arguments",args))))))));}
    String rec(String criteria){return "{\"criteria\":"+criteria+",\"requests\":[{\"requestId\":\"r1\",\"type\":\"RECOMMENDATION\",\"target\":{},\"fact\":\"DETAIL\"}]}";}
    DiningCriteriaPatch patch(TurnSemantics s){return ((RequirementChange.CriteriaPatch)s.requirementChanges().getFirst()).patch();}
    @ParameterizedTest @ValueSource(strings={"面馆","米线","酸菜鱼","韩式炸鸡"})
    void missingExplicitFoodMustExtractAndReachSearch(String food)throws Exception {
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(rec("{\"city\":\"福州市\"}")),
                response(json.writeValueAsString(Map.of("cuisine",food,"cuisineAnyOf",List.of(),"excludedCuisines",List.of()))));
        var h=new V2PilotHarness(spy(interpreter),"test-food-"+food);var turn=h.turn("在福州市找"+food,List.of());
        assertEquals(CuisineCanonicalizer.canonicalize(food),CuisineCanonicalizer.canonicalize(turn.executionCalls().getFirst().action().path("spec").path("criteria").path("cuisine").path("include").asText()));
        verify(client,times(2)).chatCompletion(anyList(),anyList(),anyMap(),anyString());
        assertEquals("CUISINE_COVERAGE_REQUIRED",interpreter.takeEvaluationAttempts().getFirst().get("validationResult"));
    }
    @Test void exactOriginalCannotSilentlyRunWithoutFood()throws Exception {
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(rec("{\"city\":\"福州市\",\"poi\":\"三坊七巷\",\"budgetHard\":50}")),
                response("{\"cuisine\":null,\"cuisineAnyOf\":[],\"excludedCuisines\":[]}"));
        var h=new V2PilotHarness(spy(interpreter),"test-no-silent-food-loss");
        assertThrows(SemanticInterpretationException.class,()->h.turn("福州市三坊七巷附近找面馆，人均50以内",List.of()));
        assertTrue(h.commits.isEmpty());assertTrue(h.calls.isEmpty());
    }
    @Test void dishCannotBeReplacedByBroadCuisineOrInventedDish(){
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(rec("{\"cuisine\":\"韩餐\"}")),
                response("{\"cuisine\":\"韩餐\",\"cuisineAnyOf\":[],\"excludedCuisines\":[]}"));
        assertThrows(SemanticInterpretationException.class,()->interpreter.interpret("找韩式炸鸡"));
    }
    @Test void noFoodAndExplicitClearNeedNoExtraction(){
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(rec("{}")),response("{\"criteria\":{},\"cleared\":[\"CUISINE\"],\"requests\":[{\"requestId\":\"r1\",\"type\":\"RECOMMENDATION\",\"target\":{},\"fact\":\"DETAIL\"}]}"));
        assertNull(patch(interpreter.interpret("附近找餐厅")).cuisine());
        var clear=interpreter.interpret("取消菜系限制，附近找餐厅");
        assertTrue(clear.requirementChanges().stream().filter(RequirementChange.CriteriaPatch.class::isInstance).map(RequirementChange.CriteriaPatch.class::cast).anyMatch(c->c.cleared().contains(RequirementChange.ClearedCriterion.CUISINE)));
        verify(client,times(2)).chatCompletion(anyList(),anyList(),anyMap(),anyString());
    }
    @Test void orPreserved(){
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(rec("{}")),response("{\"cuisine\":null,\"cuisineAnyOf\":[\"面馆\",\"米线\"],\"excludedCuisines\":[]}"));
        assertEquals(List.of("面馆","米线"),patch(interpreter.interpret("找面馆或者米线")).cuisine().anyOf());
    }
    @Test void oldFeedbackCannotSupplyNewTaskCuisine()throws Exception {
        var first=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(rec("{\"arrivalTime\":\"明天中午\"}"));
        first.put("taskDirective","START_NEW");first.put("taskDirectiveEvidence","EXPLICIT_NEW_TASK");
        first.set("feedback",json.readTree("[{\"target\":{\"ordinal\":2},\"kind\":\"POSITIVE\",\"aspect\":\"UNSPECIFIED\",\"batch\":false,\"polarity\":\"NEGATIVE\"}]"));
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(first.toString()),response("{\"cuisine\":\"面馆\",\"cuisineAnyOf\":[],\"excludedCuisines\":[]}"));
        assertThrows(SemanticInterpretationException.class,()->interpreter.interpret("第二家面馆看着不错。对了，明天中午想吃粤菜。"));
        assertEquals("CUISINE_COVERAGE_REQUIRED",interpreter.takeEvaluationAttempts().getFirst().get("validationResult"));
    }
    @Test void dietaryRepairAlsoExtractsKnownMissingFoodInOneBoundedCall()throws Exception {
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(rec("{\"spiceRequirement\":\"{}\"}")),
                response("{\"avoidedIngredients\":[],\"spiceRequirement\":null,\"cuisine\":\"面馆\",\"cuisineAnyOf\":[],\"excludedCuisines\":[]}"));
        assertEquals("面馆",patch(interpreter.interpret("找面馆")).cuisine().include());
        verify(client,times(2)).chatCompletion(anyList(),anyList(),anyMap(),anyString());
    }
    @Test void categorySynonymsAreNotDishEvidence(){
        assertEquals(CuisineCanonicalizer.canonicalize("面食"),CuisineCanonicalizer.canonicalize("面馆"));
        assertNotEquals(CuisineCanonicalizer.canonicalize("快餐"),CuisineCanonicalizer.canonicalize("炸鸡"));
        assertNotEquals(CuisineCanonicalizer.canonicalize("粉面"),CuisineCanonicalizer.canonicalize("米线"));
        assertNotEquals(CuisineCanonicalizer.canonicalize("韩餐"),CuisineCanonicalizer.canonicalize("韩式炸鸡"));
    }
}
