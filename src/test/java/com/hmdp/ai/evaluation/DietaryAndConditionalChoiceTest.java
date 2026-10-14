package com.hmdp.ai.evaluation;

import com.fasterxml.jackson.databind.*;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.v2.runtime.*;
import com.hmdp.ai.v2.semantic.*;
import com.hmdp.ai.v2.evidence.MainMealSpiceEvidence;
import com.hmdp.ai.tool.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DietaryAndConditionalChoiceTest {
    final ObjectMapper json=new ObjectMapper();final OpenAiCompatibleClient client=mock(OpenAiCompatibleClient.class);
    final SemanticInterpreter interpreter=spy(new SemanticInterpreter());
    DietaryAndConditionalChoiceTest(){ReflectionTestUtils.setField(interpreter,"objectMapper",json);ReflectionTestUtils.setField(interpreter,"aiClient",client);}
    JsonNode response(String params){return json.valueToTree(Map.of("choices",List.of(Map.of("message",Map.of("tool_calls",List.of(Map.of("function",Map.of("arguments",params))))))));}
    String rec(String criteria){return "{\"criteria\":"+criteria+",\"requests\":[{\"requestId\":\"r1\",\"type\":\"RECOMMENDATION\",\"target\":{},\"fact\":\"DETAIL\"}]}";}
    String choice(){return """
        {"criteria":{},"requests":[{"requestId":"r1","type":"FACT","fact":"NON_SPICY_MAIN_MEAL","target":{"ordinal":2}},
        {"requestId":"r2","type":"SELECT","target":{"ordinal":2}}],"relations":[{"observedRequestId":"r1","dependentRequestId":"r2","predicate":{"type":"BOOLEAN","expected":true}}]}
        """;}
    List<DecisionRecommendation> candidates(String food){var list=new ArrayList<DecisionRecommendation>();for(int n=1;n<=3;n++){var s=new DecisionRecommendation();s.setShopId(n==2?22L:(long)n);s.setShopName("TEST ONLY "+food+n);s.setCuisine(food);s.setAvgPrice(40L);list.add(s);}return list;}
    JsonNode active(JsonNode memory){for(JsonNode t:memory.path("tasks"))if(t.path("taskId").asText().equals(memory.path("activeTaskId").asText()))return t;throw new AssertionError();}
    @Test void originalC03ExtractsIngredientsAndHardSpiceThroughRealPrePost() throws Exception {
        String first=rec("{\"cuisine\":\"川菜\",\"poi\":\"万象城\",\"arrivalTime\":\"今晚下班\",\"preferences\":[{\"criterion\":\"够麻够辣\",\"importance\":\"PREFER\"}]}");
        String missing=rec("{\"poi\":\"万象城\"}");
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(first),response(missing),response("""
            {"avoidedIngredients":["海鲜","内脏"],"spiceRequirement":"不能吃太辣"}
            """));
        var h=new V2PilotHarness(interpreter,"test-c03-diet");var originals=V2ControlledPilotTest.messages().get("C03");
        var one=h.turn(originals.get(0),candidates("川菜"));String task=one.workingMemory().path("activeTaskId").asText();
        var two=h.turn(originals.get(1),candidates("川菜"));assertEquals(task,two.workingMemory().path("activeTaskId").asText());
        var criteria=active(two.workingMemory()).path("v2Criteria");assertEquals("川菜",criteria.path("cuisine").path("include").asText());
        assertEquals(json.readTree("[\"海鲜\",\"内脏\"]"),criteria.path("dietaryRestrictions").path("avoidedIngredients"));
        assertEquals("不能吃太辣",criteria.path("dietaryRestrictions").path("spiceRequirement").asText());
        assertEquals("今晚下班",criteria.path("diningTime").path("arrivalTime").asText());assertEquals("万象城",criteria.path("location").path("poi").asText());
        assertTrue(criteria.path("preferences").path("values").isEmpty());assertEquals(2,h.commits.size());
        assertEquals("NOT_EXECUTED",h.commits.getLast().path("event").path("verificationStatus").asText());
        assertTrue(two.fullResponse().path("answer").asText().contains("不能视为已满足饮食限制"));
    }
    @Test void missingIngredientOrInventedMedicalRestrictionCannotPass() throws Exception {
        String original=V2ControlledPilotTest.messages().get("C03").get(1);
        for(String values:List.of("[\"海鲜\"]","[\"海鲜\",\"内脏\",\"牛肉\"]")) {
            reset(client);when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(rec("{}")),response("{\"avoidedIngredients\":"+values+",\"spiceRequirement\":\"不能吃太辣\"}"));
            var h=new V2PilotHarness(interpreter,"test-c03-invalid");assertThrows(SemanticInterpretationException.class,()->h.turn(original,candidates("川菜")));assertNull(h.persisted);assertTrue(h.commits.isEmpty());assertTrue(h.calls.isEmpty());
        }
    }
    @Test void cuisineExclusionMisclassificationCanBeCorrectedWithoutDroppingRestriction() throws Exception {
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(rec("{\"excludedCuisines\":[\"海鲜\"]}")),
                response("{\"avoidedIngredients\":[\"海鲜\",\"内脏\"],\"spiceRequirement\":\"不能吃太辣\",\"excludedCuisines\":[]}"));
        var turn=interpreter.interpret(V2ControlledPilotTest.messages().get("C03").get(1));
        var patch=((RequirementChange.CriteriaPatch)turn.requirementChanges().getFirst()).patch();assertEquals(List.of("海鲜","内脏"),patch.dietaryRestrictions().avoidedIngredients());assertNull(patch.cuisine());
    }
    @Test void arbitraryIngredientsUseSameContractAndExistingRestrictionSurvivesOtherChanges() throws Exception {
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(rec("{\"avoidedIngredients\":[\"花生\",\"牛奶\"],\"spiceRequirement\":\"不能吃辣\"}")));
        var semantics=interpreter.interpret("不吃花生、牛奶，不能吃辣，找个餐厅");var patch=(RequirementChange.CriteriaPatch)semantics.requirementChanges().getFirst();
        var base=CriteriaPatchApplier.apply(DiningCriteria.empty(),patch);
        var next=CriteriaPatchApplier.apply(base,new RequirementChange.CriteriaPatch(new DiningCriteriaPatch(null,null,new DiningCriteria.BudgetCriteria(null,java.math.BigDecimal.valueOf(50)),null,null,null),Set.of()));
        assertEquals(base.dietaryRestrictions(),next.dietaryRestrictions());
    }
    @Test void originalC23UnknownReducedDrinkOrWrongShopEvidenceNeverSelects() throws Exception {
        for(Object evidence:Arrays.asList(null,new MainMealSpiceEvidence(22,"TEST 米线",true,MainMealSpiceEvidence.Preparation.REDUCED_SPICE,"TEST"),
                new MainMealSpiceEvidence(22,"TEST 饮料",false,MainMealSpiceEvidence.Preparation.NON_SPICY,"TEST"),
                new MainMealSpiceEvidence(44,"TEST 米线",true,MainMealSpiceEvidence.Preparation.NON_SPICY,"TEST"),
                new MainMealSpiceEvidence(22,"TEST 米线",true,MainMealSpiceEvidence.Preparation.UNKNOWN,"TEST"),
                new MainMealSpiceEvidence(22,"TEST 米线",true,MainMealSpiceEvidence.Preparation.NON_SPICY,""))) {
            var result=runChoice(evidence);assertTrue(active(result.workingMemory()).path("v2SelectedShopId").isNull());
            assertTrue(result.fullResponse().path("answer").asText().contains("暂不选店"));
        }
    }
    @Test void reliableNonSpicyMainMealEvidenceAllowsConditionalChoice() throws Exception {
        var result=runChoice(new MainMealSpiceEvidence(22,"TEST 米线",true,MainMealSpiceEvidence.Preparation.NON_SPICY,"TEST_MENU_FACT"));
        assertEquals(22,active(result.workingMemory()).path("v2SelectedShopId").asLong());
    }
    V2PilotEvaluation.TurnEvidence runChoice(Object evidence)throws Exception {
        reset(client);when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(rec("{\"poi\":\"万达负一楼\"}")),response(choice()));
        var h=new V2PilotHarness(interpreter,"test-c23-conditional");Object runtime=ReflectionTestUtils.getField(h.chat,"v2ChatOrchestrator");
        Object action=ReflectionTestUtils.getField(runtime,"actions");var registry=(AgentToolRegistry)ReflectionTestUtils.getField(action,"toolRegistry");
        var tool=registry.find("get_shop_detail");if(evidence!=null)doAnswer(i->{var result=(AgentToolResult)i.callRealMethod();result.getFacts().put("mainMealSpiceEvidence",evidence);return result;}).when(tool).execute(anyMap());
        var displayed=candidates("米线");displayed.get(0).setCuisine("酸菜鱼");displayed.get(0).setShopName("TEST ONLY 酸菜鱼");
        displayed.get(2).setCuisine("炸鸡汉堡");displayed.get(2).setShopName("TEST ONLY 炸鸡汉堡");
        var mapper=(com.hmdp.mapper.ShopMapper)ReflectionTestUtils.getField(tool,"shopMapper");
        doAnswer(i->{var c=displayed.stream().filter(s->s.getShopId().equals(((Number)i.getArgument(0)).longValue())).findFirst().orElseThrow();
            var shop=new com.hmdp.entity.Shop();shop.setId(c.getShopId());shop.setName(c.getShopName());shop.setAvgPrice(40L);shop.setAddress("TEST_ONLY_ADDRESS");return shop;}).when(mapper).selectById(any());
        var original=V2ControlledPilotTest.messages().get("C23");h.turn(original.get(0),displayed);
        return h.turn(original.get(1),List.of());
    }
    @Test void illegalCategoryBooleanStringAndUnconditionalChoiceAreRejected() throws Exception {
        for(String bad:List.of(choice().replace("\"BOOLEAN\",\"expected\":true","\"CATEGORY\",\"expected\":\"不辣\""),
                choice().replace("\"expected\":true","\"expected\":\"true\""),choice().replace("\"expected\":true","\"expected\":false"))) {
            reset(client);when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(bad));
            assertThrows(SemanticInterpretationException.class,()->interpreter.interpret(V2ControlledPilotTest.messages().get("C23").get(1)));
        }
    }
    @Test void repairCanChangeInvalidFoodCategoryIntoTypedBooleanGuard() throws Exception {
        String bad=choice().replace("\"BOOLEAN\",\"expected\":true","\"CATEGORY\",\"expected\":\"不辣\"");
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(bad),response(choice()));
        assertNotNull(interpreter.interpret(V2ControlledPilotTest.messages().get("C23").get(1)));
        assertEquals("VALID",interpreter.takeEvaluationAttempts().getLast().get("validationResult"));
    }
    @Test void inventedClearingAndIngredientInCuisineSlotCannotPass() throws Exception {
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response("{\"criteria\":{},\"cleared\":[\"DIETARY_RESTRICTIONS\"]}"));
        assertThrows(SemanticInterpretationException.class,()->interpreter.interpret("还是这个安排，找更便宜的"));
    }
    @Test void duplicatedPositiveSpicePreferenceFromRealFirstTurnDoesNotBecomeHardProhibition() throws Exception {
        String first=rec("{\"cuisine\":\"川菜\",\"poi\":\"万象城\",\"spiceRequirement\":\"够麻够辣\",\"preferences\":[{\"criterion\":\"够麻够辣\",\"importance\":\"PREFER\"}]}");
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(first),response("{\"arrivalTime\":\"今晚下班\"}"));
        var semantic=interpreter.interpret(V2ControlledPilotTest.messages().get("C03").get(0));
        var patch=((RequirementChange.CriteriaPatch)semantic.requirementChanges().getFirst()).patch();
        assertNull(patch.dietaryRestrictions());assertEquals("够麻够辣",patch.semanticPreferences().values().getFirst().criterion());assertEquals("今晚下班",patch.diningTime().arrivalTime());
    }
}
