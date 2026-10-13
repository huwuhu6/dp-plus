package com.hmdp.ai.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.*;
import com.hmdp.ai.service.*;
import com.hmdp.ai.v2.runtime.SemanticInterpreter;
import com.hmdp.ai.v2.semantic.TurnSemantics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.hmdp.ai.evaluation.V2PilotEvaluation.*;
import static com.hmdp.ai.evaluation.V2ControlledPilotTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 显式opt-in；每轮原话调用一次真实入口，保留解释器自己的契约repair，runner不重试或选优。 */
@EnabledIfSystemProperty(named="v2.realModel.enabled",matches="true")
@SpringBootTest(classes=V2RealModelPilotTest.ModelConfiguration.class,webEnvironment=SpringBootTest.WebEnvironment.NONE)
class V2RealModelPilotTest {
    @Configuration(proxyBeanMethods=false)
    @EnableConfigurationProperties(AiProperties.class)
    @Import({OpenAiCompatibleClient.class,SemanticInterpreter.class,AiModelCallTracker.class,AiModelCallObservationService.class})
    static class ModelConfiguration { @Bean ObjectMapper objectMapper(){return new ObjectMapper();} }
    @Autowired ObjectMapper json;
    @Autowired SemanticInterpreter productionInterpreter;
    @Autowired OpenAiCompatibleClient productionClient;
    @Autowired AiProperties properties;

    @Test void sixScenariosOnceWithActualSemanticModel() throws Exception {
        assertTrue(properties.isConfigured(),"model credentials unavailable; no model call made");
        String runId=System.getProperty("v2.pilot.runId","");
        assertTrue(runId.matches("[A-Za-z0-9_-]+"),"explicit unique v2.pilot.runId required");
        Path directory=Path.of("target/eval-pilot",runId);Files.createDirectories(directory);
        // 已启动的run不允许覆盖，避免完整基线失败后反复跑并挑最好结果。
        Files.writeString(directory.resolve("STARTED.json"),"{\"startedAt\":\""+java.time.Instant.now()+"\"}",StandardOpenOption.CREATE_NEW);
        var originals=messages();List<Plan> plans=new ArrayList<>();
        for(String id:List.of("S04","S08","S09","S13","C06","C15")) {
            Plan a=plan(id);List<String> boundaries=new ArrayList<>(a.controlledBoundaries());boundaries.remove("SemanticInterpreter");
            Map<String,String> facts=new LinkedHashMap<>(a.contextFacts());
            facts.put("modelHistory","Production: system + current user message only; no injected conversation history");
            facts.put("requestLocation","null, same as original controlled requests; test geocoder is not model context");
            plans.add(new Plan(id,a.specVersion(),a.freezeSha(),a.repositorySha(),a.dirtyWorkspace(),Mode.CONTROLLED_CONTEXT_REAL_MODEL,a.factBranch(),a.items(),boundaries,facts));
        }
        save(directory.resolve("plan.json"),plans);
        Map<String,Object> metadata=new LinkedHashMap<>();metadata.put("model",properties.getModel());metadata.put("provider",properties.getProvider());
        var endpoint=java.net.URI.create(properties.getBaseUrl());
        metadata.put("endpoint",endpoint.getScheme()+"://"+endpoint.getHost()+(endpoint.getPort()<0?"":":"+endpoint.getPort())+endpoint.getPath());
        metadata.put("temperature",0.1);metadata.put("timeoutMs",properties.getTimeoutMs());metadata.put("runnerRetry",false);
        metadata.put("productionRepair","at most one contract repair per user turn; retained, recorded, not result selection");
        metadata.put("javaVersion",System.getProperty("java.version"));metadata.put("springBootVersion",org.springframework.boot.SpringBootVersion.getVersion());
        metadata.put("repositorySha",plans.getFirst().repositorySha());metadata.put("dirtyWorkspace",plans.getFirst().dirtyWorkspace());
        for(String file:List.of("src/main/java/com/hmdp/ai/v2/runtime/SemanticInterpreter.java","src/main/java/com/hmdp/ai/client/OpenAiCompatibleClient.java","src/test/java/com/hmdp/ai/evaluation/V2ControlledPilotTest.java"))
            metadata.put(file+".sha256",sha256(Files.readAllBytes(Path.of(file))));
        save(directory.resolve("metadata.json"),metadata);

        var modelEvidence=new V2ModelCallEvidence(json);OpenAiCompatibleClient observedClient=spy(productionClient);
        doAnswer(i->modelEvidence.observe(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),()->{
            try { return (JsonNode)i.callRealMethod(); } catch(RuntimeException e){throw e;} catch(Throwable e){throw new AssertionError(e);}
        })).when(observedClient).chatCompletion(anyList(),anyList(),anyMap(),eq("V2_SEMANTIC_INTERPRETATION"));
        ReflectionTestUtils.setField(productionInterpreter,"aiClient",observedClient);
        List<ScenarioResult> results=new ArrayList<>();
        try {
            for(Plan declared:plans) {
                String id=declared.scenarioId();SemanticInterpreter observedInterpreter=spy(productionInterpreter);
                V2PilotHarness h=new V2PilotHarness(observedInterpreter,"test-real-"+runId+"-"+id);
                doAnswer(i->{h.actualSemantic=(TurnSemantics)i.callRealMethod();return h.actualSemantic;}).when(observedInterpreter).interpret(anyString());
                List<Map<String,Object>> detailed=new ArrayList<>();boolean modelFailure=false;
                for(int n=0;n<originals.get(id).size();n++) {
                    modelEvidence.calls.clear();h.actualSemantic=null;
                    ConversationWorkingMemory before=memory(h);Map<String,Object> observation=new LinkedHashMap<>();
                    observation.put("turn",n+1);observation.put("userMessage",originals.get(id).get(n));observation.put("before",json.valueToTree(before));
                    long started=System.nanoTime();
                    try {
                        TurnEvidence turn=h.turn(originals.get(id).get(n),n==0?shops(id,false):id.equals("S13")||n==2?List.of():shops(id,true));
                        observation.put("returned",true);observation.put("evidence",turn);
                    } catch(RuntimeException failure) {
                        observation.put("returned",false);observation.put("failureType",failure.getClass().getName());
                        observation.put("failureCategory",failure instanceof com.hmdp.ai.v2.runtime.SemanticModelAvailabilityException||modelEvidence.calls.stream().anyMatch(c->Boolean.FALSE.equals(c.get("success")))?"MODEL_AVAILABILITY":failure instanceof com.hmdp.ai.v2.runtime.SemanticInterpretationException?"SEMANTIC_CONTRACT":"EXECUTION_OR_INFRASTRUCTURE");
                        ChatMessageRequest request=new ChatMessageRequest();request.setChatId(h.chatId);request.setMessage(originals.get(id).get(n));
                        TurnEvidence missing=new TurnEvidence(n+1,json.valueToTree(request),json.nullNode(),json.valueToTree(memory(h)),
                                h.actualSemantic==null?null:json.valueToTree(Map.of("value",h.actualSemantic)),List.copyOf(h.calls),"OBSERVED_TEST_BOUNDARY");
                        h.turns.add(missing);observation.put("evidence",missing);modelFailure=true;
                    }
                    observation.put("durationMs",(System.nanoTime()-started)/1_000_000);observation.put("commits",List.copyOf(h.commits));
                    observation.put("runtimeEvents",List.copyOf(h.events));observation.put("modelCalls",List.copyOf(modelEvidence.calls));
                    observation.put("semanticAttempts",observedInterpreter.takeEvaluationAttempts());detailed.add(observation);
                    save(directory.resolve(id+"-turns.json"),detailed); // 每轮持久化证据；进程中断不丢已完成的调用。
                }
                ScenarioResult result=evaluate(declared,h,modelFailure);results.add(result);
                save(directory.resolve(id+"-result.json"),result);write(json,directory.resolve("report.json"),report(plans,results));
            }
            write(json,directory.resolve("report.json"),report(plans,results));
            save(directory.resolve("COMPLETED.json"),Map.of("completedAt",java.time.Instant.now().toString(),"scenarioCount",results.size()));
            assertEquals(6,results.size()); // JUnit只验证运行/记录完成，不要求业务通过。
        } finally { ReflectionTestUtils.setField(productionInterpreter,"aiClient",productionClient); }
    }

    private ScenarioResult evaluate(Plan plan,V2PilotHarness h,boolean callFailed) throws Exception {
        String id=plan.scenarioId();TurnEvidence first=h.turns.getFirst(),last=h.turns.getLast();
        String old=first.workingMemory().path("activeTaskId").asText();
        Gate gate=displayGate(first,old,"V2-1",List.of(11L,22L,33L),Stage.FIRST_TURN_COVERAGE);
        if(id.equals("C15")&&gate.readiness()==Readiness.READY) {
            Gate second=displayGate(h.turns.get(1),old,"V2-2",List.of(44L,55L),Stage.BUSINESS_TURN);
            gate=second.readiness()==Readiness.READY?new Gate(Readiness.READY,Stage.FIRST_TURN_COVERAGE,
                    List.of("turns/1/fullResponse","turns/1/workingMemory","turns/2/fullResponse","turns/2/workingMemory"),"actual first B and second D/E identities independently established"):second;
        }
        Map<String,Boolean> checks=new LinkedHashMap<>();
        if(!callFailed&&gate.readiness()==Readiness.READY) {
            JsonNode oldCriteria=task(last,old)==null?json.nullNode():task(last,old).path("v2Criteria");
            switch(id) {
                case "S04" -> {
                    String food=oldCriteria.path("cuisine").path("include").asText();
                    // 原话明确要求日料；只按实际语义和状态判定，不向模型补充Fixture条件。
                    checks.put("M1",food.isBlank()?null:h.matches(Map.of("criteria.cuisine.include","日料","criteria.location",Map.of("equals",task(first,old).path("v2Criteria").path("location"))),h.taskProjection(last,old)));
                    JsonNode candidates=last.fullResponse().path("decision").path("recommendations");
                    checks.put("M2",!candidates.isEmpty()&&java.util.stream.StreamSupport.stream(candidates.spliterator(),false).allMatch(s->s.path("avgPrice").asLong()==40));
                }
                case "S08" -> {
                    String cuisine=oldCriteria.path("cuisine").path("include").asText();
                    checks.put("M1",List.of("冷面","寿司").contains(cuisine)
                            &&h.matches(Map.of("criteria.cuisine.include",cuisine),h.taskProjection(last,old)));
                    checks.put("M2",List.of("冷面","寿司").contains(cuisine)&&h.matches(Map.of("criteria.location",Map.of("equals",task(first,old).path("v2Criteria").path("location"))),h.taskProjection(last,old)));
                }
                case "S09" -> {
                    boolean negative=false;
                    for(JsonNode f:task(last,old).path("v2FeedbackLedger"))if("V2-1".equals(f.path("batchId").asText())&&"NEGATIVE".equals(f.path("batchPolarity").asText()))negative=true;
                    checks.put("M1",negative&&disjoint(h,first,last));
                }
                case "S13" -> {
                    checks.put("M1",identityClarification(last));
                    checks.put("F1",h.matches(Map.of("selectedShopId",Map.of("null",true)),h.taskProjection(last,old))&&last.executionCalls().isEmpty());
                }
                case "C06" -> {checks.put("M1",positiveOldFeedback(last,old));checks.put("M3",arrangementIsolation(h,first,last,old));}
                case "C15" -> {
                    checks.put("M2",boundToFirstB(h,last));
                    checks.put("M4",h.matches(Map.of("criteria",Map.of("equals",h.taskProjection(h.turns.get(1),old).get("criteria")),"selectedShopId",Map.of("null",true)),h.taskProjection(last,old)));
                }
            }
        }
        List<ItemResult> items=new ArrayList<>();
        for(ItemPlan item:plan.items()) {
            String key=item.itemId().substring(id.length()+1);Boolean checked=checks.get(key);
            String reason=callFailed?"model/turn exception: no successful business judgement; see per-turn failure category":checks.containsKey(key)?
                    "same pilot obligation; real semantic/state/execution predicate; no exact soft-budget target imposed":missingEvidence(id,key);
            if(checked==null&&checks.containsKey(key))reason="evidence insufficient: S04 cuisine not represented or C06 new arrangement absent; no inference of proven violation";
            if(Boolean.FALSE.equals(checked))reason+="; actual predicate failures="+json.writeValueAsString(h.assertionFailures);
            items.add(check(item,gate,checked,checks.containsKey(key)?List.of("turns/"+last.turn()+"/fullResponse","turns/"+last.turn()+"/workingMemory","turns/"+last.turn()+"/executionCalls"):List.of(),reason,checked==null?Stage.OBSERVATION:Stage.BUSINESS_TURN));
        }
        return finish(plan,gate,h.turns,items);
    }

    static String missingEvidence(String id,String item) {
        return switch(id) {
            case "S04" -> switch(item){case "M3","F3"->"requires independent price-unit/source and answer fact review; controlled averages alone insufficient";case "F1"->"requires combined final answer/criteria violation check; no full item assertion executed";default->"requires acceptance tolerance and response review; no internal exact-budget standard allowed";};
            case "S08" -> item.equals("M3")?"menu/supply facts not independently supplied; cuisine tag insufficient":"OR/exclusion and full response scope not fully adjudicated";
            case "S09" -> item.equals("F2")?"no global/permanent exclusion observation; one nonrepeat insufficient":"actual floor/mall facts and combined scope/answer check absent";
            case "S13" -> item.equals("M2")?"full breakfast/location-context continuity check not executed":"address claim vs clarification/unknown-response content check not executed";
            case "C06" -> switch(item){case "M2"->"time/party size/environment not expressible in current model contract; full business request review required";case "M4","F3"->"city/reference date are fixture annotations, not supplied model context; real station uniqueness and OR evidence incomplete";case "M5"->"capacity/environment/opening evidence unavailable";default->"combined response/intent/isolation prohibitions not fully adjudicated";};
            default -> switch(item){case "M1"->"minority/chain/mall attributes not provided; preference semantics not in current criteria schema";case "M3"->"separate address/signature dishes response evidence required; DETAIL alone does not distinguish both";case "M5"->"signature dishes/merchant attributes unknown; test detail identity is not full fact proof";default->"full historical identity/response/selection prohibition check not executed";};
        };
    }
    private ConversationWorkingMemory memory(V2PilotHarness h) throws Exception { return h.persisted==null?new ConversationWorkingMemory():json.readValue(h.persisted.getMemoryJson(),ConversationWorkingMemory.class); }
    private void save(Path file,Object value) throws Exception { json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(),value); }
    static String sha256(byte[] bytes) throws Exception { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
