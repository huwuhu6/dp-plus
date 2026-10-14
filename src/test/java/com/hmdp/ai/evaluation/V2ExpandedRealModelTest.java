package com.hmdp.ai.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.HmDianPingApplication;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.service.LocationResolutionProvider;
import com.hmdp.ai.v2.grounding.V2LocationResolver;
import com.hmdp.ai.v2.plan.ExecutionAction;
import com.hmdp.ai.v2.runtime.*;
import com.hmdp.mapper.ShopMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.hmdp.ai.evaluation.V2PilotEvaluation.*;

/** 扩展观察入口：复用隔离 memory 和真实主链，用真实只读事实边界替换六试点的商户替身。 */
@EnabledIfSystemProperty(named="v2.expanded.enabled",matches="true")
@SpringBootTest(classes=HmDianPingApplication.class,webEnvironment=SpringBootTest.WebEnvironment.NONE)
class V2ExpandedRealModelTest {
    static final List<String> IDS=List.of("S01","S02","S03","S05","S06","S10","C03","C04","C10","C23");
    @Autowired ObjectMapper json;
    @Autowired SemanticInterpreter interpreter;
    @Autowired OpenAiCompatibleClient client;
    @Autowired AiProperties properties;
    @Autowired V2ActionHandler actions;
    @Autowired ShopMapper shops;
    @Autowired LocationResolutionProvider locations;

    @Test void tenFrozenScenariosOnceUsingActualReadOnlyFacts() throws Exception {
        assertTrue(properties.isConfigured(),"real model credentials unavailable");
        String runId=System.getProperty("v2.pilot.runId","");assertTrue(runId.matches("[A-Za-z0-9_-]+"));
        Path directory=Path.of("target/eval-pilot",runId);Files.createDirectories(directory);
        Files.writeString(directory.resolve("STARTED.json"),"{}",StandardOpenOption.CREATE_NEW);
        var originals=V2ControlledPilotTest.messages(); // 同时验证原文与冻结 SHA 一致。
        List<String> selected=List.of(System.getProperty("v2.expanded.scenarios",String.join(",",IDS)).split(","));
        assertTrue(!selected.isEmpty() && IDS.containsAll(selected) && selected.stream().distinct().count()==selected.size());
        String sha=new String(new ProcessBuilder("git","rev-parse","HEAD").start().getInputStream().readAllBytes()).trim();
        boolean dirty=!new String(new ProcessBuilder("git","status","--porcelain").start().getInputStream().readAllBytes()).isBlank();
        String spec=Files.readString(Path.of("doc/eval/V2自然语言多轮评测-业务验收规范-v1.md"));
        List<Plan> plans=new ArrayList<>();
        for(String id:selected) {
            var scene=Pattern.compile("(?ms)^### "+id+"｜(.*?)(?=^### [SC]\\d{2}｜|^## 第三)").matcher(spec);assertTrue(scene.find());
            var item=Pattern.compile("(?m)^- ([MF]\\d+)(?:【| )").matcher(scene.group(1));List<ItemPlan> items=new ArrayList<>();
            while(item.find())items.add(new ItemPlan(id+"-"+item.group(1),true,true,true));
            plans.add(new Plan(id,"v1.1","156b81c35e3bfc5c87813b9f5f6a6c46b4ab0b88",sha,dirty,Mode.REAL_END_TO_END,
                    "Real read-only merchant data; unknown menu/geographic/subjective facts remain unverified",items,
                    List.of("WorkingMemoryVersionService","ChatMemoryService","ConversationEventService"),
                    Map.of("requestLocation","null: no invented device location","modelHistory","production system + current user only",
                            "source","actual MySQL / retrieval / location / read-only tools; session persistence isolated")));
        }
        save(directory.resolve("plan.json"),plans);
        save(directory.resolve("metadata.json"),Map.of("repositorySha",sha,"model",properties.getModel(),"timeoutMs",properties.getTimeoutMs(),
                "modelTransportRetry",json.valueToTree(properties.getModelRetry()),
                "runnerRetry",false,"locationProviderAvailable",locations.isAvailable(),"productionSemanticSha256",
                V2RealModelPilotTest.sha256(Files.readAllBytes(Path.of("src/main/java/com/hmdp/ai/v2/runtime/SemanticInterpreter.java")))));
        var model=new V2ModelCallEvidence(json);var observedClient=spy(client);
        doAnswer(i->model.observe(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),()->{
            try{return (JsonNode)i.callRealMethod();}catch(RuntimeException e){throw e;}catch(Throwable e){throw new AssertionError(e);}
        },observedClient::lastTransportAttempts)).when(observedClient).chatCompletion(anyList(),anyList(),anyMap(),eq("V2_SEMANTIC_INTERPRETATION"));
        ReflectionTestUtils.setField(interpreter,"aiClient",observedClient);
        List<ScenarioResult> results=new ArrayList<>();
        try {
            for(Plan plan:plans) {
                String id=plan.scenarioId();var observedInterpreter=spy(interpreter);
                var h=new V2PilotHarness(observedInterpreter,"test-expanded-"+runId+"-"+id);
                doAnswer(i->{h.actualSemantic=(com.hmdp.ai.v2.semantic.TurnSemantics)i.callRealMethod();return h.actualSemantic;})
                        .when(observedInterpreter).interpret(anyString());
                // 仅替换 test harness 的外部边界；不改 Production 对象和持久化，不注入固定候选。
                Object runtime=ReflectionTestUtils.getField(h.chat,"v2ChatOrchestrator");
                var observedActions=spy(actions);
                doAnswer(i->{ExecutionAction action=i.getArgument(0);
                    try {var result=(ExecutionObservation)i.callRealMethod();h.calls.add(observedCall(json,action,result,"ACTUAL read-only external boundary",List.of()));return result;}
                    catch(RuntimeException failure){h.calls.add(observedFailure(json,action,failure,"ACTUAL external boundary failure",List.of()));throw failure;}
                }).when(observedActions).execute(any());
                ReflectionTestUtils.setField(runtime,"actions",observedActions);
                ReflectionTestUtils.setField(runtime,"shopMapper",shops);
                ReflectionTestUtils.setField(runtime,"locationResolver",new V2LocationResolver(locations));
                List<Map<String,Object>> detailed=new ArrayList<>();
                for(String original:originals.get(id)) {
                    model.calls.clear();h.actualSemantic=null;
                    Map<String,Object> turn=new LinkedHashMap<>();turn.put("turn",detailed.size()+1);turn.put("userMessage",original);
                    turn.put("before",h.persisted==null?json.nullNode():json.readTree(h.persisted.getMemoryJson()));
                    try {turn.put("evidence",h.turn(original,List.of()));turn.put("returned",true);}
                    catch(RuntimeException failure) {
                        turn.put("returned",false);turn.put("failureType",failure.getClass().getName());
                        turn.put("failureCategory",model.calls.stream().anyMatch(c->Boolean.FALSE.equals(c.get("success")))?"MODEL_AVAILABILITY":
                                failure instanceof SemanticInterpretationException?"SEMANTIC_CONTRACT":"EXECUTION_OR_INFRASTRUCTURE");
                    }
                    turn.put("after",h.persisted==null?json.nullNode():json.readTree(h.persisted.getMemoryJson()));
                    turn.put("commits",List.copyOf(h.commits));turn.put("runtimeEvents",List.copyOf(h.events));
                    turn.put("executionCalls",List.copyOf(h.calls));turn.put("modelCalls",List.copyOf(model.calls));
                    turn.put("semanticAttempts",observedInterpreter.takeEvaluationAttempts());detailed.add(turn);
                    save(directory.resolve(id+"-turns.json"),detailed);
                }
                // 就绪要求涵盖冻结展示与事实，不能仅用 nonEmpty 当作完整前提；先保守待人工证据核验。
                Gate gate=ExpandedScenarioReadiness.assess(id,json.valueToTree(detailed.getFirst()));
                List<ItemResult> items=plan.items().stream().map(i->new ItemResult(i.itemId(),Status.UNVERIFIED,Stage.OBSERVATION,
                        List.of(id+"-turns.json"),"raw evidence captured; no business assertion executed; independent review required")).toList();
                var result=finish(plan,gate,h.turns,items);results.add(result);
                save(directory.resolve(id+"-result.json"),result);write(json,directory.resolve("report.json"),report(plans,results));
            }
            save(directory.resolve("COMPLETED.json"),Map.of("scenarios",results.size()));assertEquals(selected.size(),results.size());
        } finally {ReflectionTestUtils.setField(interpreter,"aiClient",client);}
    }
    private void save(Path file,Object value) throws Exception {json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(),value);}
}
