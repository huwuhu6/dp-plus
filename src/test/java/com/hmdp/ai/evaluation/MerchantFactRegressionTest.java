package com.hmdp.ai.evaluation;

import com.fasterxml.jackson.databind.*;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.v2.runtime.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.hmdp.ai.v2.semantic.*;
import com.hmdp.ai.tool.*;
import com.hmdp.ai.v2.evidence.ProductAttributeEvidence;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MerchantFactRegressionTest {
    final ObjectMapper json=new ObjectMapper();final OpenAiCompatibleClient client=mock(OpenAiCompatibleClient.class);
    final SemanticInterpreter interpreter=spy(new SemanticInterpreter());
    MerchantFactRegressionTest(){ReflectionTestUtils.setField(interpreter,"objectMapper",json);ReflectionTestUtils.setField(interpreter,"aiClient",client);}
    JsonNode response(String args){return json.valueToTree(Map.of("choices",List.of(Map.of("message",Map.of("tool_calls",List.of(Map.of("function",Map.of("arguments",args))))))));}
    V2PilotHarness prepared(String id,boolean price,Object meal)throws Exception{
        var h=new V2PilotHarness(interpreter,"test-fact-"+id);ControlledFourFixtures.configure(h,id,price,meal);
        var list=ControlledFourFixtures.shops(id,false);if(!price)list.forEach(s->s.setAvgPrice(null));
        h.turn(V2ControlledPilotTest.messages().get(id).getFirst(),ControlledFourFixtures.first(id),list);return h;
    }
    @Test void originalS02GeneralMisclassificationMustReachActualMerchantPrice()throws Exception{
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response("""
            {"criteria":{"spiceRequirement":"不能吃辣"},"requests":[{"requestId":"r1","type":"GENERAL","topic":"椰子鸡价格","target":{},"fact":"DETAIL"}]}
            """));
        var h=prepared("S02",true,null);var last=h.turn(V2ControlledPilotTest.messages().get("S02").get(1),List.of());
        assertTrue(last.fullResponse().path("answer").asText().contains("人均 40 元"));
        assertTrue(last.fullResponse().path("answer").asText().contains("不能吃辣"));
        assertEquals(22,last.executionCalls().getFirst().action().path("shop").path("shopId").asInt());
    }
    @Test void originalS06DetailCannotSilentlyDropProductQuestion()throws Exception{
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response("""
            {"criteria":{},"requests":[{"requestId":"r1","type":"FACT","fact":"DETAIL","target":{"ordinal":2}}]}
            """),response("""
            {"criteria":{},"requests":[{"requestId":"r1","type":"FACT","fact":"PRODUCT","question":"无骨","target":{"ordinal":2}}]}
            """));
        var h=prepared("S06",true,null);var last=h.turn(V2ControlledPilotTest.messages().get("S06").get(1),List.of());
        String answer=last.fullResponse().path("answer").asText();assertTrue(answer.contains("无法确认")&&answer.contains("无骨"));
        assertEquals(22,last.executionCalls().getFirst().action().path("shop").path("shopId").asInt());
    }
    @Test void originalC23MustRenderFactAndExecutedSelectionTogether()throws Exception{
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response("""
            {"criteria":{},"requests":[{"requestId":"r1","type":"FACT","fact":"NON_SPICY_MAIN_MEAL","target":{"ordinal":2}},
            {"requestId":"r2","type":"SELECT","target":{"ordinal":2}}],"relations":[{"observedRequestId":"r1","dependentRequestId":"r2","predicate":{"type":"BOOLEAN","expected":true}}]}
            """));
        var h=prepared("C23",true,ControlledFourFixtures.mainMeal());var last=h.turn(V2ControlledPilotTest.messages().get("C23").get(1),List.of());
        String answer=last.fullResponse().path("answer").asText();assertTrue(answer.contains("主餐不辣证据")&&answer.contains("已为你选定"));
    }
    String fact(String kind,String target,String question){return "{\"criteria\":{},\"requests\":[{\"requestId\":\"r1\",\"type\":\"FACT\",\"fact\":\""+kind+"\",\"target\":"+target+(question==null?"":",\"question\":\""+question+"\"")+"}]}";}
    @Test void unknownPriceHasNoNumericEvidenceOrInventedTotal()throws Exception{
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(fact("PRICE","{\"ordinal\":2}",null)));
        var h=prepared("S02",false,null);var last=h.turn("第二家多少钱？",List.of());String answer=last.fullResponse().path("answer").asText();
        assertTrue(answer.contains("无法确认")&&answer.contains("价格"));assertFalse(answer.contains("null"));
        var observation=last.executionCalls().getFirst().observation();assertEquals("UNKNOWN",observation.path("merchantFact").path("state").asText());assertTrue(observation.path("value").isNull());
    }
    @Test void generalKnowledgeAndUnshownMerchantNeverGetRewrittenOrGuessed()throws Exception{
        String general="{\"requests\":[{\"requestId\":\"r1\",\"type\":\"GENERAL\",\"topic\":\"椰子鸡价格\"}]}";
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(general));
        var h=prepared("S02",true,null);var one=h.turn("一般椰子鸡大概多少钱？",List.of());assertEquals("GeneralAnswerAction",one.executionCalls().getFirst().actionType());
        var empty=new V2PilotHarness(interpreter,"test-no-history");var two=empty.turn("椰子鸡大概多少钱？",List.of());assertEquals("GeneralAnswerAction",two.executionCalls().getFirst().actionType());
    }
    @Test void duplicateDescriptionAndOrdinalNameConflictClarifyBeforeTools()throws Exception{
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(fact("PRICE","{\"name\":\"椰子鸡\"}",null)),response(fact("PRICE","{\"name\":\"椰子鸡\",\"ordinal\":1}",null)));
        var h=new V2PilotHarness(interpreter,"test-ambiguous-price");ControlledFourFixtures.configure(h,"S02",true,null);
        var shops=ControlledFourFixtures.shops("S02",false);shops.get(2).setShopName("TEST_ONLY另一家椰子鸡");shops.get(2).setCuisine("椰子鸡锅");
        h.turn("找晚餐",ControlledFourFixtures.first("S02"),shops);
        var two=h.turn("椰子鸡多少钱？",List.of());assertEquals("CLARIFYING",two.fullResponse().path("decision").path("status").asText());assertTrue(h.calls.isEmpty());assertTrue(h.commits.isEmpty());
        h=prepared("S02",true,null);var three=h.turn("第一家椰子鸡多少钱？",List.of());assertEquals("CLARIFYING",three.fullResponse().path("decision").path("status").asText());assertTrue(h.calls.isEmpty());assertTrue(h.commits.isEmpty());
    }
    @Test void historicalPriceUsesOriginalBatchAndDoesNotRestoreOldConditions()throws Exception{
        var h=prepared("S02",true,null);String old=h.turns.getFirst().workingMemory().path("activeTaskId").asText();
        var replacement=interpreter.parse(json.readTree("{\"requests\":[{\"requestId\":\"r1\",\"type\":\"RECOMMENDATION\"}]}"));
        var shops=ControlledFourFixtures.shops("S02",true);shops.get(1).setShopName("TEST_ONLY新店");shops.get(1).setCuisine("砂锅粥");
        h.turn("换一批",replacement,shops);var before=h.turns.getLast().workingMemory();
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(fact("PRICE","{\"name\":\"椰子鸡\"}",null)));
        var last=h.turn("最开始推荐的椰子鸡价格是多少？",List.of());assertEquals(22,last.executionCalls().getFirst().action().path("shop").path("shopId").asInt());
        assertEquals("V2-1",last.executionCalls().getFirst().action().path("shop").path("batchId").asText());
        assertEquals(com.hmdp.ai.evaluation.V2PilotEvaluation.task(h.turns.get(1),old).path("v2Criteria"),com.hmdp.ai.evaluation.V2PilotEvaluation.task(last,old).path("v2Criteria"));
    }
    @Test void productEvidenceMustMatchMerchantAttributeAndHaveSource()throws Exception{
        for(Object evidence:Arrays.asList(null,new ProductAttributeEvidence(44,"无骨",true,"TEST_CACHE"),
                new ProductAttributeEvidence(22,"无糖",true,"TEST_CACHE"),new ProductAttributeEvidence(22,"无骨",true,""),
                new ProductAttributeEvidence(22,"无骨",null,"TEST_CACHE"),new ProductAttributeEvidence(22,"无骨",true,"TEST_CACHE"),
                new ProductAttributeEvidence(22,"无骨",false,"TEST_CONFIRMED_ABSENCE"))){
            reset(client);when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(fact("PRODUCT","{\"ordinal\":2}","无骨")));
            var h=prepared("S06",true,null);var runtime=ReflectionTestUtils.getField(h.chat,"v2ChatOrchestrator");var actions=ReflectionTestUtils.getField(runtime,"actions");
            var registry=(AgentToolRegistry)ReflectionTestUtils.getField(actions,"toolRegistry");var original=registry.find("get_shop_detail");
            BaseAgentTool provider=new BaseAgentTool(){public String name(){return original.name();}public String description(){return original.description();}
                public Map<String,Object> parameterSchema(){return original.parameterSchema();}public AgentToolResult execute(Map<String,Object> input){var r=original.execute(input);if(evidence!=null)r.getFacts().put("productAttributeEvidence",evidence);return r;}};
            when(registry.find("get_shop_detail")).thenReturn(provider);
            var last=h.turn(V2ControlledPilotTest.messages().get("S06").get(1),List.of());var o=last.executionCalls().getFirst().observation();
            boolean known=evidence instanceof ProductAttributeEvidence e && e.supports(22,"无骨");
            assertEquals(known?"KNOWN":"UNKNOWN",o.path("merchantFact").path("state").asText());
            assertEquals("无骨",last.executionCalls().getFirst().action().path("question").asText());
            if(!known)assertTrue(last.fullResponse().path("answer").asText().contains("无法确认"));
            else assertEquals(((ProductAttributeEvidence)evidence).available(),o.path("value").path("value").booleanValue());
        }
    }
    @Test void inventedOrOmittedProductAttributeFailsWithZeroStateCommit()throws Exception{
        for(String property:List.of("无糖","韩国炸鸡")){
            reset(client);when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(fact("PRODUCT","{\"ordinal\":2}",property)));
            var h=prepared("S06",true,null);String before=h.persisted.getMemoryJson();
            assertThrows(SemanticInterpretationException.class,()->h.turn(V2ControlledPilotTest.messages().get("S06").get(1),List.of()));
            assertEquals(before,h.persisted.getMemoryJson());assertTrue(h.calls.isEmpty());assertTrue(h.commits.isEmpty());
        }
    }
    @Test void multipleFactsAreNotOverwrittenAndFactsAloneNeverConfirmSelection()throws Exception{
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response("""
            {"requests":[{"requestId":"r1","type":"FACT","fact":"PRICE","target":{"ordinal":2}},
            {"requestId":"r2","type":"FACT","fact":"PRODUCT","question":"无骨","target":{"ordinal":2}}]}
            """));
        var h=prepared("S06",true,null);var last=h.turn("第二家人均多少钱，有无骨的吗？",List.of());String answer=last.fullResponse().path("answer").asText();
        assertTrue(answer.contains("人均 40 元")&&answer.contains("无法确认")&&answer.contains("无骨"));assertFalse(answer.contains("已为你选定"));
    }
    @Test void foodDescriptionGroundingUsesLiteralDisplayedDataNotBroadCuisineCanonicalization(){
        var batch=new com.hmdp.ai.v2.grounding.TaskView.RecommendationBatchView("B",List.of(
                new com.hmdp.ai.v2.grounding.TaskView.ShopView(22,"测试甲店","椰子鸡"),
                new com.hmdp.ai.v2.grounding.TaskView.ShopView(33,"测试乙店","刺身")));
        var view=new com.hmdp.ai.v2.grounding.TaskView("task",0,"DINING",null,batch,null);
        var context=new com.hmdp.ai.v2.grounding.EffectiveTaskContext(view,false);
        var resolver=new com.hmdp.ai.v2.grounding.GroundingResolver();
        var price=new TurnSemantics(TaskDirective.CONTINUE,List.of(),List.of(),List.of(new UserRequest.FactQueryRequest("price",new EntityReference.NamedEntityRef("椰子鸡"),UserRequest.FactType.PRICE)),List.of());
        var grounded=resolver.ground(price,context,"椰子鸡多少钱？");assertEquals(22,((com.hmdp.ai.v2.grounding.GroundedReference.ShopIdentity)grounded.requests().getFirst().operands().getFirst()).shopId());
        var sushi=new TurnSemantics(TaskDirective.CONTINUE,List.of(),List.of(),List.of(new UserRequest.FactQueryRequest("price",new EntityReference.NamedEntityRef("寿司"),UserRequest.FactType.PRICE)),List.of());
        assertInstanceOf(com.hmdp.ai.v2.grounding.GroundingResult.NeedsClarification.class,resolver.resolve(sushi,context,"寿司多少钱？"));
    }
    @Test void priceAndAddressCanCoexistWithoutRewritingDetailOrDroppingAnswers()throws Exception{
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response("""
            {"requests":[{"requestId":"r1","type":"FACT","fact":"PRICE","target":{"ordinal":2}},
            {"requestId":"r2","type":"FACT","fact":"DETAIL","target":{"ordinal":2}}]}
            """));
        var h=prepared("S02",true,null);var last=h.turn("第二家多少钱？地址也给我。",List.of());String answer=last.fullResponse().path("answer").asText();
        assertTrue(answer.contains("人均 40 元")&&answer.contains("地址 TEST_ONLY"));
        verify(client,times(1)).chatCompletion(anyList(),anyList(),anyMap(),anyString());
    }
}
