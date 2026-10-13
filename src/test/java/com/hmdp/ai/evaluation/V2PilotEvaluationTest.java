package com.hmdp.ai.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.*;
import com.hmdp.ai.service.AiConversationEvaluationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static com.hmdp.ai.evaluation.V2PilotEvaluation.*;
import static org.junit.jupiter.api.Assertions.*;

class V2PilotEvaluationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Gate ready = new Gate(Readiness.READY, Stage.PREPARATION, List.of("fixture independently checked"), "ready");
    private final ItemPlan item = new ItemPlan("S08-M1", true, true, true);
    private Plan plan(String id) {
        return new Plan(id, "v1.1", "156b81c35e3bfc5c87813b9f5f6a6c46b4ab0b88", "156b81c35e3bfc5c87813b9f5f6a6c46b4ab0b88",
                false, Mode.CONTROLLED_SEMANTICS, "test-only facts known", List.of(new ItemPlan(id+"-M1", true, true, true)), List.of("memory/semantics"),Map.of());
    }
    @Test void missingCheckAndMissingEvidenceNeverPass() {
        for (Boolean checked : Arrays.asList(null, true)) {
            ItemResult result = check(item, ready, checked, List.of(), "no observation", Stage.OBSERVATION);
            assertEquals(Status.UNVERIFIED, result.status());
            assertFalse(finish(plan("S08"), ready, List.of(), List.of(result)).corePassed());
        }
    }
    @Test void readinessAndCompletionUseFrozenDenominatorsIncludingMissingRuns() {
        Plan a=plan("S08"), b=plan("S09");
        ScenarioResult passed=finish(a,ready,List.of(),List.of(check(item,ready,true,List.of("observed"),"verified",Stage.BUSINESS_TURN)));
        Report report=report(List.of(a,b),List.of(passed));
        assertEquals(new Metric(1,2,0.5),report.summary().readinessRate());
        assertEquals(new Metric(1,1,1.0),report.summary().readyPassRate());
        assertEquals(new Metric(1,2,0.5),report.summary().fullCompletionRate());
        assertEquals(Status.PRECONDITION_NOT_MET,report.scenarios().get(1).items().getFirst().status());
    }
    @Test void notReadyAndConflictingEvidenceCannotCountAsSuccessfulCheck() {
        for (Readiness value : List.of(Readiness.NOT_READY,Readiness.EVIDENCE_CONFLICT)) {
            Gate gate=new Gate(value,Stage.FIRST_TURN_COVERAGE,List.of("display"),"missing/conflicting");
            ItemResult result=check(item,gate,true,List.of("claimed success"),"yes",Stage.BUSINESS_TURN);
            assertEquals(Status.PRECONDITION_NOT_MET,result.status());
            Report report=report(List.of(plan("S08")),List.of(finish(plan("S08"),gate,List.of(),List.of(result))));
            assertEquals(0,report.summary().corePassed()); assertNull(report.summary().readyPassRate().value());
        }
    }
    @Test void completeFailureCountsAsCompletionButNotPass() {
        ScenarioResult failed=finish(plan("S08"),ready,List.of(),List.of(check(item,ready,false,List.of("actual violation"),"wrong",Stage.BUSINESS_TURN)));
        Summary summary=report(List.of(plan("S08")),List.of(failed)).summary();
        assertEquals(1,summary.fullyAdjudicated()); assertEquals(0,summary.corePassed()); assertEquals(1,summary.businessFailed());
    }
    @Test void forgedSuccessFlagsAndEmptyEvidenceAreRechecked() {
        ScenarioResult forged=new ScenarioResult(plan("S08"),ready,List.of(),List.of(new ItemResult("S08-M1",Status.SATISFIED,Stage.BUSINESS_TURN,List.of(),"")),true,true,false);
        assertEquals(0,report(List.of(plan("S08")),List.of(forged)).summary().corePassed());
    }
    @Test void applicabilityAndPlanCannotChangeAfterExecution() {
        assertThrows(IllegalArgumentException.class,()->finish(plan("S08"),ready,List.of(),List.of(new ItemResult("S08-M1",Status.NOT_APPLICABLE,Stage.OBSERVATION,List.of(),"skip"))));
        assertThrows(IllegalArgumentException.class,()->report(List.of(plan("S08")),List.of(finish(plan("S09"),ready,List.of(),List.of()))));
        assertThrows(IllegalArgumentException.class,()->finish(plan("S08"),ready,List.of(),List.of(new ItemResult("S08-M2",Status.UNVERIFIED,Stage.OBSERVATION,List.of(),""))));
    }
    @Test void independentDisplayGateChecksIdentityOrderAndNameAndCopiesMemory() {
        ConversationWorkingMemory memory=new ConversationWorkingMemory(); DecisionTaskState task=new DecisionTaskState();
        task.setTaskId("task"); memory.getTasks().add(task); memory.setActiveTaskId("task");
        RecommendationBatch batch=new RecommendationBatch(); batch.setBatchId("B1"); task.getRecommendationBatches().add(batch);
        for (long id : List.of(1L,2L)) { RecommendationCandidateRef ref=new RecommendationCandidateRef();ref.setShopId(id);ref.setShopName("test"+id);batch.getCandidates().add(ref); }
        ChatMessageResponse response=new ChatMessageResponse(); DecisionResponse decision=new DecisionResponse();response.setDecision(decision);
        for (long id : List.of(1L,2L)) { DecisionRecommendation r=new DecisionRecommendation();r.setShopId(id);r.setShopName("test"+id);decision.getRecommendations().add(r); }
        response.setAnswer("完整回答".repeat(150));
        TurnEvidence evidence=capture(json,1,new ChatMessageRequest(),response,memory,null,List.of(),false);
        assertEquals(Readiness.READY,displayGate(evidence,"task","B1",List.of(1L,2L),Stage.PREPARATION).readiness());
        assertEquals(600,evidence.fullResponse().path("answer").asText().length());
        Collections.reverse(batch.getCandidates());
        assertEquals(Readiness.READY,displayGate(evidence,"task","B1",List.of(1L,2L),Stage.PREPARATION).readiness());
        TurnEvidence conflict=capture(json,1,new ChatMessageRequest(),response,memory,null,List.of(),false);
        assertEquals(Readiness.EVIDENCE_CONFLICT,displayGate(conflict,"task","B1",List.of(1L,2L),Stage.PREPARATION).readiness());
        assertEquals(Readiness.NOT_READY,displayGate(conflict,"task","B1",List.of(8L,9L),Stage.FIRST_TURN_COVERAGE).readiness());
        Collections.reverse(batch.getCandidates());batch.getCandidates().getFirst().setShopName("wrong branch");
        assertEquals(Readiness.EVIDENCE_CONFLICT,displayGate(capture(json,1,new ChatMessageRequest(),response,memory,null,List.of(),false),"task","B1",List.of(1L,2L),Stage.PREPARATION).readiness());
    }
    @Test void reusableV2PredicatesRejectOldCuisineWrongTaskAndWrongHistoricalIdentity() {
        AiConversationEvaluationService evaluator=new AiConversationEvaluationService(); ReflectionTestUtils.setField(evaluator,"objectMapper",json);
        Map<String,Object> expected=Map.of("criteria.cuisine.include","寿司","taskId","old-task",
                "groundedEntities",List.of(Map.of("shopId",22L,"batchId","V2-1","ordinal",2)));
        Map<String,Object> correct=Map.of("criteria",Map.of("cuisine",Map.of("include","寿司")),"taskId","old-task",
                "groundedEntities",List.of(Map.of("shopId",22L,"batchId","V2-1","ordinal",2)));
        assertTrue(evaluator.assertEvaluationProjection(2,expected,correct).isEmpty());
        for (String wrong : List.of("criteria","taskId","groundedEntities")) {
            Map<String,Object> actual=new HashMap<>(correct);
            actual.put(wrong,switch(wrong) { case "criteria" -> Map.of("cuisine",Map.of("include","火锅"));case "taskId" -> "new-task";default -> List.of(Map.of("shopId",55L,"batchId","V2-2","ordinal",2)); });
            assertEquals(1,evaluator.assertEvaluationProjection(2,expected,actual).size());
        }
        assertThrows(IllegalArgumentException.class,()->evaluator.assertEvaluationProjection(2,Map.of(),correct));
        assertThrows(IllegalArgumentException.class,()->evaluator.assertEvaluationProjection(2,Map.of("criteria.cusine",Map.of("notSet",true)),correct));
        assertThrows(IllegalArgumentException.class,()->evaluator.assertEvaluationProjection(2,Map.of("criteria",Map.of("location",Map.of())),correct));
    }
}
