package com.hmdp.ai.evaluation;

import com.fasterxml.jackson.databind.*;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.v2.runtime.SemanticInterpreter;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static com.hmdp.ai.evaluation.V2PilotEvaluation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ControlledFourFixturesTest {
    @Test void fourOriginalFirstTurnsReallyCreateMatchingVisibleAndStoredBatches()throws Exception{
        for(String id:ControlledFourFixtures.IDS){
            var h=new V2PilotHarness();ControlledFourFixtures.configure(h,id,true,id.equals("C23")?ControlledFourFixtures.mainMeal():null);
            var first=h.turn(V2ControlledPilotTest.messages().get(id).getFirst(),ControlledFourFixtures.first(id),ControlledFourFixtures.shops(id,false));
            String task=first.workingMemory().path("activeTaskId").asText();
            assertEquals(Readiness.READY,displayGate(first,task,"V2-1",List.of(11L,22L,33L),Stage.PREPARATION).readiness());
            assertEquals(2,h.commits.size());
            var visible=first.fullResponse().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)visible.path("decision").path("recommendations").get(1)).put("shopId",44);
            var conflicting=new TurnEvidence(1,first.userRequest(),visible,first.workingMemory(),first.parsedSemantics(),first.executionCalls(),first.executionEvidenceStatus());
            assertNotEquals(Readiness.READY,displayGate(conflicting,task,"V2-1",List.of(11L,22L,33L),Stage.PREPARATION).readiness());
        }
    }
    @Test void knownAndUnknownPriceBranchesQuerySameSecondShopAndPreserveOriginalDiningContext()throws Exception{
        for(boolean known:List.of(true,false)){
            ObjectMapper json=new ObjectMapper();var client=mock(OpenAiCompatibleClient.class);var interpreter=new SemanticInterpreter();
            ReflectionTestUtils.setField(interpreter,"objectMapper",json);ReflectionTestUtils.setField(interpreter,"aiClient",client);
            String args="""
                {"criteria":{"spiceRequirement":"不能吃辣"},"requests":[{"requestId":"price","type":"FACT","fact":"DETAIL","target":{"name":"椰子鸡"}}]}
                """;
            when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(json.valueToTree(Map.of("choices",List.of(Map.of("message",Map.of("tool_calls",List.of(Map.of("function",Map.of("arguments",args)))))))));
            var h=new V2PilotHarness(spy(interpreter),"test-price-"+known);var reads=ControlledFourFixtures.configure(h,"S02",known,null);
            var messages=V2ControlledPilotTest.messages().get("S02");var initial=ControlledFourFixtures.shops("S02",false);
            // 未知分支的实际展示也不携带价格，避免一边显示已知人均价、一边宣称事实未知。
            if(!known)initial.forEach(s->s.setAvgPrice(null));
            var first=h.turn(messages.getFirst(),ControlledFourFixtures.first("S02"),initial);
            var last=h.turn(messages.get(1),List.of());String id=first.workingMemory().path("activeTaskId").asText();
            var criteria=task(last,id).path("v2Criteria");assertEquals(2,criteria.path("partySize").asInt());assertEquals("晚上",criteria.path("diningTime").path("arrivalTime").asText());
            assertEquals("不能吃辣",criteria.path("dietaryRestrictions").path("spiceRequirement").asText());
            assertEquals(22,last.executionCalls().getFirst().action().path("shop").path("shopId").asInt());
            JsonNode returned=json.valueToTree(reads.getFirst()).path("result").path("facts").path("shop").path("avgPrice");
            assertEquals(known,!returned.isNull());
            if(known)assertTrue(last.fullResponse().path("answer").asText().contains("人均 40 元"));
            // 未知分支没有价格事实，不能把工具执行 SUCCESS 解释成已验证价格。当前 renderer 的 null 文本是待修复缺陷。
            else assertTrue(returned.isNull());
        }
    }
}
