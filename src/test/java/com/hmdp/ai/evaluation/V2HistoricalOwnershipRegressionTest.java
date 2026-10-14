package com.hmdp.ai.evaluation;

import com.hmdp.ai.v2.semantic.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.hmdp.ai.evaluation.V2ControlledPilotTest.*;
import static com.hmdp.ai.evaluation.V2PilotEvaluation.*;
import static org.junit.jupiter.api.Assertions.*;

class V2HistoricalOwnershipRegressionTest {
    static TurnSemantics recommend(TaskDirective directive, String food, String place, List<EntityFeedback> feedback) {
        var patch=new DiningCriteriaPatch(new DiningCriteria.LocationCriteria(null,null,place),new DiningCriteria.CuisineCriteria(food,List.of()),null,null,null,null);
        return new TurnSemantics(directive,List.of(new RequirementChange.CriteriaPatch(patch,Set.of())),feedback,List.of(new UserRequest.RecommendationRequest("r1")),List.of());
    }
    @Test void originalC15WrongModelOrdinalMustNeverQueryCurrent44() throws Exception {
        V2PilotHarness h=new V2PilotHarness();
        var first=h.turn(messages().get("C15").get(0),recommend(TaskDirective.START_NEW,"牛排","国贸",List.of()),shops("C15",false));
        var second=h.turn(messages().get("C15").get(1),new TurnSemantics(TaskDirective.CONTINUE,List.of(),List.of(),List.of(new UserRequest.AlternativesRequest("r1",2)),List.of()),shops("C15",true));
        var last=h.turn(messages().get("C15").get(2),new TurnSemantics(TaskDirective.CONTINUE,List.of(),List.of(),List.of(new UserRequest.FactQueryRequest("r1",new EntityReference.OrdinalRef(1),UserRequest.FactType.DETAIL)),List.of()),List.of());
        assertTrue(boundToFirstB(h,last));
        String old=first.workingMemory().path("activeTaskId").asText();
        assertEquals(task(second,old).path("v2Criteria"),task(last,old).path("v2Criteria"));
        assertTrue(last.executionCalls().stream().noneMatch(c->c.action().path("shop").path("shopId").asLong()==44));
    }
    @Test void originalC06FeedbackPersistsOnOldTaskAndNewCriteriaStaySeparate() throws Exception {
        V2PilotHarness h=new V2PilotHarness();
        var first=h.turn(messages().get("C06").getFirst(),recommend(TaskDirective.START_NEW,"面","公司附近",List.of()),shops("C06",false));
        String old=first.workingMemory().path("activeTaskId").asText();
        var feedback=new EntityFeedback.EntityFeedbackItem(new EntityReference.OrdinalRef(2),EntityFeedback.FeedbackKind.POSITIVE,EntityFeedback.FeedbackAspect.UNSPECIFIED);
        var patch=new DiningCriteriaPatch(new DiningCriteria.LocationCriteria(null,null,"南站"),new DiningCriteria.CuisineCriteria("杭帮菜",List.of()),null,null,
                new DiningCriteria.DiningTimeCriteria("明天中午"),new DiningCriteria.SemanticPreferences(List.of(
                new DiningCriteria.SemanticPreference("6人聚餐",DiningCriteria.Importance.MUST),new DiningCriteria.SemanticPreference("上档次",DiningCriteria.Importance.PREFER)),List.of()));
        var semantics=new TurnSemantics(TaskDirective.START_NEW,List.of(new RequirementChange.CriteriaPatch(patch,Set.of())),List.of(feedback),List.of(new UserRequest.RecommendationRequest("r1")),List.of());
        var last=h.turn(messages().get("C06").get(1),semantics,shops("C06",true));
        assertTrue(positiveOldFeedback(last,old));
        assertNotEquals(old,last.workingMemory().path("activeTaskId").asText());
        assertEquals(task(first,old).path("v2Criteria"),task(last,old).path("v2Criteria"));
        assertTrue(task(last,old).path("v2SelectedShopId").isNull());
        var fresh=task(last,last.workingMemory().path("activeTaskId").asText());
        assertEquals("南站",fresh.path("v2Criteria").path("location").path("poi").asText());
        assertEquals("明天中午",fresh.path("v2Criteria").path("diningTime").path("arrivalTime").asText());
        assertTrue(fresh.path("v2Criteria").path("preferences").toString().contains("6人聚餐"));
        assertTrue(fresh.path("v2Criteria").path("preferences").toString().contains("上档次"));
        assertTrue(fresh.path("v2FeedbackLedger").isEmpty());
    }
    @Test void invalidOldFeedbackDoesNotCommitAnyNewTaskOrPartialFeedback() throws Exception {
        V2PilotHarness h=new V2PilotHarness();
        h.turn(messages().get("C06").getFirst(),recommend(TaskDirective.START_NEW,"面","公司附近",List.of()),shops("C06",false));
        String before=h.persisted.getMemoryJson();int version=h.persisted.getVersion();
        var wrong=new EntityFeedback.EntityFeedbackItem(new EntityReference.OrdinalRef(9),EntityFeedback.FeedbackKind.POSITIVE,EntityFeedback.FeedbackAspect.UNSPECIFIED);
        var last=h.turn(messages().get("C06").get(1),recommend(TaskDirective.START_NEW,"杭帮菜","南站",List.of(wrong)),shops("C06",true));
        assertEquals(before,h.persisted.getMemoryJson());assertEquals(version,h.persisted.getVersion());
        assertTrue(last.executionCalls().isEmpty());assertEquals("CLARIFYING",last.fullResponse().path("decision").path("status").asText());
    }
    @Test void searchFailureKeepsValidUserFactsIsolatedWithoutFabricatedBatch() throws Exception {
        V2PilotHarness h=new V2PilotHarness();
        var first=h.turn(messages().get("C06").getFirst(),recommend(TaskDirective.START_NEW,"面","公司附近",List.of()),shops("C06",false));
        String old=first.workingMemory().path("activeTaskId").asText();
        h.retrievalFailure=new IllegalStateException("isolated provider failure");
        var feedback=new EntityFeedback.EntityFeedbackItem(new EntityReference.OrdinalRef(2),EntityFeedback.FeedbackKind.POSITIVE,EntityFeedback.FeedbackAspect.UNSPECIFIED);
        var last=h.turn(messages().get("C06").get(1),recommend(TaskDirective.START_NEW,"杭帮菜","南站",List.of(feedback)),shops("C06",true));
        assertTrue(positiveOldFeedback(last,old));
        assertEquals(task(first,old).path("v2Criteria"),task(last,old).path("v2Criteria"));
        assertTrue(task(last,last.workingMemory().path("activeTaskId").asText()).path("recommendationBatches").isEmpty());
        assertEquals("PARTIAL",last.fullResponse().path("decision").path("status").asText());
    }
    @Test void historicalAmbiguityCannotExecuteWrongTool() throws Exception {
        V2PilotHarness h=new V2PilotHarness();
        h.turn("推荐牛排",recommend(TaskDirective.START_NEW,"牛排","国贸",List.of()),shops("C15",false));
        var last=h.turn("之前推荐的那家不存在的店地址",new TurnSemantics(TaskDirective.CONTINUE,List.of(),List.of(),List.of(new UserRequest.FactQueryRequest("r1",new EntityReference.OrdinalRef(1),UserRequest.FactType.DETAIL)),List.of()),List.of());
        assertTrue(last.executionCalls().isEmpty());assertEquals("CLARIFYING",last.fullResponse().path("decision").path("status").asText());
    }
    @Test void originalC15ReplaysModelFunctionThroughActualSemanticInterpreter() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var client=org.mockito.Mockito.mock(com.hmdp.ai.client.OpenAiCompatibleClient.class);
        String raw="""
            {"taskDirective":"CONTINUE","taskDirectiveEvidence":"NONE","requests":[{"requestId":"r1","type":"FACT","target":{"ordinal":1},"fact":"DETAIL"}]}
            """;
        var response=json.valueToTree(Map.of("choices",List.of(Map.of("message",Map.of("tool_calls",List.of(Map.of("function",Map.of("arguments",raw))))))));
        org.mockito.Mockito.when(client.chatCompletion(org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyString())).thenReturn(response);
        var interpreter=org.mockito.Mockito.spy(new com.hmdp.ai.v2.runtime.SemanticInterpreter());
        org.springframework.test.util.ReflectionTestUtils.setField(interpreter,"aiClient",client);
        org.springframework.test.util.ReflectionTestUtils.setField(interpreter,"objectMapper",json);
        V2PilotHarness h=new V2PilotHarness(interpreter,"test-replay-c15");
        h.turn(messages().get("C15").getFirst(),recommend(TaskDirective.START_NEW,"牛排","国贸",List.of()),shops("C15",false));
        h.turn(messages().get("C15").get(1),new TurnSemantics(TaskDirective.CONTINUE,List.of(),List.of(),List.of(new UserRequest.AlternativesRequest("r1",2)),List.of()),shops("C15",true));
        var last=h.turn(messages().get("C15").get(2),List.of());
        assertTrue(boundToFirstB(h,last));
        org.mockito.Mockito.verify(client).chatCompletion(org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyString());
    }
    @Test void originalC15ModelTaskRefMustQueryMerchantWithoutRestoringOldTask() throws Exception {
        V2PilotHarness h=new V2PilotHarness();
        var first=h.turn(messages().get("C15").getFirst(),recommend(TaskDirective.START_NEW,"牛排","国贸",List.of()),shops("C15",false));
        String old=first.workingMemory().path("activeTaskId").asText();
        var second=h.turn("新开一项需求找南站杭帮菜",recommend(TaskDirective.START_NEW,"杭帮菜","南站",List.of()),shops("C06",true));
        String current=second.workingMemory().path("activeTaskId").asText();
        var taskRef=new EntityReference.TaskRef(new TaskSelector.Earliest());
        var last=h.turn(messages().get("C15").get(2),new TurnSemantics(TaskDirective.RESTORE,List.of(),List.of(),
                List.of(new UserRequest.FactQueryRequest("r1",taskRef,UserRequest.FactType.DETAIL)),List.of(),List.of(taskRef)),List.of());
        assertTrue(boundToFirstB(h,last));assertEquals(current,last.workingMemory().path("activeTaskId").asText());
        assertEquals(task(second,current).path("v2Criteria"),task(last,current).path("v2Criteria"));
        assertEquals(task(first,old).path("v2Criteria"),task(last,old).path("v2Criteria"));
        assertEquals("SUSPENDED",task(last,old).path("v2Lifecycle").asText());
    }
}
