package com.hmdp.ai.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.v2.runtime.SemanticInterpreter;
import com.hmdp.ai.v2.semantic.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.hmdp.ai.evaluation.V2ControlledPilotTest.*;
import static com.hmdp.ai.evaluation.V2HistoricalOwnershipRegressionTest.recommend;
import static com.hmdp.ai.evaluation.V2PilotEvaluation.task;

class C06SemanticContractTest {
    final ObjectMapper json=new ObjectMapper();
    final OpenAiCompatibleClient client=mock(OpenAiCompatibleClient.class);
    SemanticInterpreter interpreter() {
        var result=spy(new SemanticInterpreter());
        ReflectionTestUtils.setField(result,"aiClient",client);ReflectionTestUtils.setField(result,"objectMapper",json);return result;
    }
    com.fasterxml.jackson.databind.JsonNode response(String raw) {
        return json.valueToTree(Map.of("choices",List.of(Map.of("message",Map.of("tool_calls",List.of(Map.of("function",Map.of("arguments",raw))))))));
    }
    String complete() {return """
        {"taskDirective":"START_NEW","taskDirectiveEvidence":"EXPLICIT_NEW_TASK",
         "criteria":{"poi":"南站","cuisineAnyOf":["杭帮菜","粤菜"],"arrivalTime":"明天中午","partySize":6,
          "preferences":[{"criterion":"环境上档次","importance":"PREFER"},{"criterion":"家庭聚餐","importance":"PREFER"}]},
         "feedback":[{"target":{"ordinal":2},"kind":"POSITIVE","aspect":"UNSPECIFIED"}],
         "requests":[{"requestId":"r1","type":"RECOMMENDATION","target":{},"fact":"DETAIL"}]}
        """;}
    @Test void originalCompoundTurnUsesActualParserGroundingReducersAndAtomicCommits() throws Exception {
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(complete()));
        var h=new V2PilotHarness(interpreter(),"test-c06-contract");
        var first=h.turn(messages().get("C06").getFirst(),recommend(TaskDirective.START_NEW,"面","公司附近",List.of()),shops("C06",false));
        String old=first.workingMemory().path("activeTaskId").asText();
        var last=h.turn(messages().get("C06").get(1),shops("C06",true));
        assertTrue(positiveOldFeedback(last,old));
        assertEquals(task(first,old).path("v2Criteria"),task(last,old).path("v2Criteria"));
        var fresh=task(last,last.workingMemory().path("activeTaskId").asText());
        assertEquals(Set.of("杭帮菜","粤菜"),new HashSet<>(json.convertValue(fresh.path("v2Criteria").path("cuisine").path("anyOf"),List.class)));
        assertEquals(6,fresh.path("v2Criteria").path("partySize").asInt());
        assertEquals("明天中午",fresh.path("v2Criteria").path("diningTime").path("arrivalTime").asText());
        assertEquals(2,fresh.path("v2Criteria").path("preferences").path("values").size());
        assertTrue(fresh.path("v2SelectedShopId").isNull());assertTrue(fresh.path("v2FeedbackLedger").isEmpty());
        assertEquals(2,h.commits.size());
        assertEquals("NOT_EXECUTED",h.commits.getLast().path("event").path("verificationStatus").asText());
        assertTrue(last.fullResponse().path("answer").asText().contains("尚未核实"));
        assertTrue(h.commits.getFirst().path("memory").path("tasks").get(0).path("v2FeedbackLedger").size()>0);
        verify(client,times(1)).chatCompletion(anyList(),anyList(),anyMap(),anyString());
    }
    @Test void firstOmissionIsRejectedAndRepairPreservesAllCompoundConditions() throws Exception {
        String missing="""
            {"taskDirective":"START_NEW","taskDirectiveEvidence":"EXPLICIT_NEW_TASK","criteria":{"poi":"南站"},
             "requests":[{"requestId":"r1","type":"RECOMMENDATION","target":{},"fact":"DETAIL"}]}
            """;
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(missing),response(complete()));
        var parser=interpreter();var semantics=parser.interpret(messages().get("C06").get(1));
        var patch=assertInstanceOf(RequirementChange.CriteriaPatch.class,semantics.requirementChanges().getFirst()).patch();
        assertEquals(List.of("杭帮菜","粤菜"),patch.cuisine().anyOf());assertEquals(6,patch.partySize());
        assertEquals(1,semantics.entityFeedback().size());assertEquals(TaskDirective.START_NEW,semantics.taskDirective());
        var attempts=parser.takeEvaluationAttempts();assertEquals(2,attempts.size());
        assertEquals("CUISINE_COVERAGE_REQUIRED",attempts.getFirst().get("validationResult"));assertEquals("VALID",attempts.getLast().get("validationResult"));
        var input=org.mockito.ArgumentCaptor.forClass(List.class);
        verify(client,times(2)).chatCompletion(input.capture(),anyList(),anyMap(),anyString());
        String repair=input.getAllValues().getLast().toString();assertTrue(repair.contains("cuisineAnyOf"));
        var repairMessages=(List<Map<String,Object>>)input.getAllValues().getLast();
        assertFalse(repairMessages.getFirst().get("content").toString().contains("杭帮菜"));
        assertEquals(messages().get("C06").get(1),repairMessages.getLast().get("content"));
    }
    @Test void repeatedModelOmissionLeavesNoPartialFeedbackOrTaskCommit() throws Exception {
        String missing="""
            {"taskDirective":"START_NEW","taskDirectiveEvidence":"EXPLICIT_NEW_TASK","criteria":{"poi":"南站"},
             "feedback":[{"target":{"ordinal":2},"kind":"POSITIVE","aspect":"UNSPECIFIED"}],
             "requests":[{"requestId":"r1","type":"RECOMMENDATION","target":{},"fact":"DETAIL"}]}
            """;
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(missing));
        var h=new V2PilotHarness(interpreter(),"test-c06-failure");
        h.turn(messages().get("C06").getFirst(),recommend(TaskDirective.START_NEW,"面","公司附近",List.of()),shops("C06",false));
        String before=h.persisted.getMemoryJson();int version=h.persisted.getVersion();
        assertThrows(com.hmdp.ai.v2.runtime.SemanticInterpretationException.class,()->h.turn(messages().get("C06").get(1),shops("C06",true)));
        assertEquals(before,h.persisted.getMemoryJson());assertEquals(version,h.persisted.getVersion());assertTrue(h.commits.isEmpty());assertTrue(h.calls.isEmpty());
    }
    @Test void parserRejectsInvalidOrSilentlyLostFields() throws Exception {
        var parser=interpreter();
        for(String fields:List.of("\"cuisine\":\"日料\",\"cuisineAnyOf\":[\"川菜\"]","\"cuisineAnyOf\":\"日料\"","\"partySize\":0",
                "\"partySize\":\"6\"","\"arrivalTime\":{}","\"people\":6","\"preferences\":[{\"criterion\":\"安静\",\"importance\":\"UNKNOWN\"}]"))
            assertThrows(IllegalArgumentException.class,()->parser.parse(json.readTree("{\"criteria\":{"+fields+"}}")),fields);
        assertThrows(IllegalArgumentException.class,()->parser.parse(json.readTree("{\"partySize\":6}")));
    }
    @Test void singleCuisineAndIndependentOrAreParsedWithoutTaskFieldOverwrite() throws Exception {
        var parser=interpreter();
        var single=parser.parse(json.readTree("{\"criteria\":{\"cuisine\":\"日料\",\"arrivalTime\":\"周六晚上\",\"partySize\":4,\"preferences\":[{\"criterion\":\"安静\",\"importance\":\"PREFER\"}]}}"));
        var patch=assertInstanceOf(RequirementChange.CriteriaPatch.class,single.requirementChanges().getFirst()).patch();
        assertEquals(List.of("日料"),patch.cuisine().acceptedCuisines());assertEquals(4,patch.partySize());assertEquals("周六晚上",patch.diningTime().arrivalTime());
        var either=parser.parse(json.readTree("{\"criteria\":{\"cuisineAnyOf\":[\"川菜\",\"日料\"]}}"));
        assertEquals(List.of("川菜","日料"),assertInstanceOf(RequirementChange.CriteriaPatch.class,either.requirementChanges().getFirst()).patch().cuisine().acceptedCuisines());
    }
    @Test void missingExplicitTimePartyOrAmbienceRequiresRepairRatherThanFabricatedValues() throws Exception {
        for(String field:List.of("arrivalTime","partySize","preferences")) {
            var root=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(complete());((com.fasterxml.jackson.databind.node.ObjectNode)root.path("criteria")).remove(field);
            reset(client);when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(root.toString()));
            var parser=interpreter();assertThrows(com.hmdp.ai.v2.runtime.SemanticInterpretationException.class,()->parser.interpret(messages().get("C06").get(1)));
            assertEquals("DINING_DETAILS_REQUIRED",parser.takeEvaluationAttempts().getFirst().get("validationResult"));
        }
    }
    @Test void singleBranchCannotSilentlyEraseExplicitOrAlternative() throws Exception {
        var root=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(complete());
        var criteria=(com.fasterxml.jackson.databind.node.ObjectNode)root.path("criteria");criteria.remove("cuisineAnyOf");criteria.put("cuisine","杭帮菜");
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(root.toString()),response(complete()));
        var parser=interpreter();var actual=parser.interpret(messages().get("C06").get(1));
        assertEquals(List.of("杭帮菜","粤菜"),assertInstanceOf(RequirementChange.CriteriaPatch.class,actual.requirementChanges().getFirst()).patch().cuisine().anyOf());
        assertEquals("CUISINE_COVERAGE_REQUIRED",parser.takeEvaluationAttempts().getFirst().get("validationResult"));
    }
    @Test void targetedRepairKeepsFirstDetailsAndOldFeedbackThroughRealPrePost() throws Exception {
        var first=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(complete());
        ((com.fasterxml.jackson.databind.node.ObjectNode)first.path("criteria")).remove("cuisineAnyOf");
        var fixed=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(complete());
        var changed=(com.fasterxml.jackson.databind.node.ObjectNode)fixed.path("criteria");
        changed.remove(List.of("arrivalTime","partySize","preferences","poi"));fixed.putArray("feedback");
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(first.toString()),response(fixed.toString()));
        var interpreter=interpreter();var h=new V2PilotHarness(interpreter,"test-c06-repair-merge");
        var oldTurn=h.turn(messages().get("C06").getFirst(),recommend(TaskDirective.START_NEW,"面","公司附近",List.of()),shops("C06",false));
        String old=oldTurn.workingMemory().path("activeTaskId").asText();
        var last=h.turn(messages().get("C06").get(1),shops("C06",true));
        assertTrue(positiveOldFeedback(last,old));assertEquals(task(oldTurn,old).path("v2Criteria"),task(last,old).path("v2Criteria"));
        var fresh=task(last,last.workingMemory().path("activeTaskId").asText()).path("v2Criteria");
        assertEquals(6,fresh.path("partySize").asInt());assertEquals("明天中午",fresh.path("diningTime").path("arrivalTime").asText());
        assertEquals("南站",fresh.path("location").path("poi").asText());assertEquals(2,fresh.path("cuisine").path("anyOf").size());
        assertEquals(2,fresh.path("preferences").path("values").size());assertEquals(2,h.commits.size());
        assertTrue(interpreter.takeEvaluationAttempts().getLast().get("fieldDecisions").toString().contains("feedback=PRESERVED_FIRST"));
    }
}
