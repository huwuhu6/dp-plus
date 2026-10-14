package com.hmdp.ai.evaluation;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.hmdp.ai.evaluation.V2PilotEvaluation.Readiness.*;

class ExpandedScenarioReadinessTest {
    final ObjectMapper json=new ObjectMapper();
    JsonNode valid() throws Exception {return json.readTree("""
        {"evidence":{"fullResponse":{"decision":{"recommendations":[
        {"shopId":1,"shopName":"TEST 广式茶餐厅 A","cuisine":"粤菜"},{"shopId":2,"shopName":"TEST 广式茶餐厅 B","cuisine":"粤菜"}]}}},
        "after":{"activeTaskId":"t","tasks":[{"taskId":"t","v2SearchAnchor":{"latitude":26,"longitude":119},
        "recommendationBatches":[{"batchId":"b","candidates":[{"shopId":1,"shopName":"TEST 广式茶餐厅 A"},{"shopId":2,"shopName":"TEST 广式茶餐厅 B"}]}]}]}}
        """);}
    @Test void actualMatchingDisplayCanBeReadyInsteadOfConstantNotReady() throws Exception {assertEquals(READY,ExpandedScenarioReadiness.assess("S10",valid()).readiness());}
    @Test void wrongOrderIsEvidenceConflict() throws Exception {var root=valid();((com.fasterxml.jackson.databind.node.ObjectNode)root.path("after").path("tasks").get(0).path("recommendationBatches").get(0).path("candidates").get(0)).put("shopId",2);
        assertEquals(EVIDENCE_CONFLICT,ExpandedScenarioReadiness.assess("S10",root).readiness());}
    @Test void WrongCategoryAndNoPositionDoNotBecomeReady() throws Exception {var root=valid();((com.fasterxml.jackson.databind.node.ObjectNode)root.path("evidence").path("fullResponse").path("decision").path("recommendations").get(0)).put("cuisine","火锅").put("shopName","TEST 火锅");
        ((com.fasterxml.jackson.databind.node.ObjectNode)root.path("after").path("tasks").get(0).path("recommendationBatches").get(0).path("candidates").get(0)).put("shopName","TEST 火锅");
        assertEquals(NOT_READY,ExpandedScenarioReadiness.assess("S10",root).readiness());}
    @Test @EnabledIfSystemProperty(named="v2.readiness.replay",matches="true")
    void replayExistingEvidenceWithoutModelCallsOrOverwritingOldResults() throws Exception {
        Path input=Path.of(System.getProperty("v2.readiness.input","../target/eval-pilot/real-expanded-ten-retry-20261014-once"));
        Path output=Path.of("target/eval-pilot/readiness-recheck-20261014");Files.createDirectories(output);
        List<Map<String,Object>> rows=new ArrayList<>();
        for(String id:V2ExpandedRealModelTest.IDS) {
            JsonNode first=json.readTree(Files.readString(input.resolve(id+"-turns.json"))).get(0);
            var gate=ExpandedScenarioReadiness.assess(id,first);
            Map<String,Object> row=new LinkedHashMap<>();row.put("scenarioId",id);row.put("firstUserMessage",first.path("userMessage"));
            row.put("firstFunctionArguments",first.path("semanticAttempts").get(0).path("functionArguments"));
            row.put("display",first.path("evidence").path("fullResponse").path("decision").path("recommendations"));
            row.put("workingMemory",first.path("after"));row.put("gate",gate);row.put("source",input.resolve(id+"-turns.json").toString());rows.add(row);
        }
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("gates.json").toFile(),rows);
        assertEquals(10,rows.size()); // 不断言必须0/10，也不把未就绪记成成功。
    }
}
