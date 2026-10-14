package com.hmdp.ai.evaluation;

import com.fasterxml.jackson.databind.*;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.v2.runtime.*;
import com.hmdp.ai.v2.semantic.TurnSemantics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import static com.hmdp.ai.evaluation.V2PilotEvaluation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 四场景配置适配；第一轮是受控前提准备，第二轮只有真实 Production 语义调用，不做 runner 重试。 */
@EnabledIfSystemProperty(named="v2.controlledFour.enabled",matches="true")
@SpringBootTest(classes=V2RealModelPilotTest.ModelConfiguration.class,webEnvironment=SpringBootTest.WebEnvironment.NONE)
class V2ControlledFourRealModelTest {
    @Autowired ObjectMapper json;
    @Autowired SemanticInterpreter interpreter;
    @Autowired OpenAiCompatibleClient client;
    @Autowired AiProperties properties;
    @Test void fourScenariosOnce() throws Exception {
        assertTrue(properties.isConfigured());assertEquals("deepseek-v4-flash-0731",properties.getModel());
        assertEquals(60000,properties.getTimeoutMs());
        String run=System.getProperty("v2.pilot.runId","");assertTrue(run.matches("[A-Za-z0-9_-]+"));
        Path dir=Path.of("target/eval-pilot",run);Files.createDirectories(dir);
        Files.writeString(dir.resolve("STARTED.json"),"{\"startedAt\":\""+java.time.Instant.now()+"\"}",StandardOpenOption.CREATE_NEW);
        var originals=V2ControlledPilotTest.messages();var plans=new ArrayList<Plan>();
        String sha=git("rev-parse","HEAD"),spec=Files.readString(Path.of("doc/eval/V2自然语言多轮评测-业务验收规范-v1.md"));
        boolean dirty=!git("status","--porcelain").isBlank();
        for(String id:ControlledFourFixtures.IDS){
            Matcher section=Pattern.compile("(?ms)^### "+id+"｜(.*?)(?=^### [SC]\\d{2}｜|^## 第三)").matcher(spec);assertTrue(section.find());
            Matcher items=Pattern.compile("(?m)^- ([MF]\\d+)\\b").matcher(section.group(1));List<ItemPlan> planned=new ArrayList<>();
            while(items.find())planned.add(new ItemPlan(id+"-"+items.group(1),true,true,true));
            plans.add(new Plan(id,"v1.1","156b81c35e3bfc5c87813b9f5f6a6c46b4ab0b88",sha,dirty,
                    Mode.CONTROLLED_CONTEXT_REAL_MODEL,ControlledFourFixtures.branch(id),planned,
                    List.of("first-turn semantic preparation ONLY","ShopRetrievalEngine","LocationResolutionProvider","ShopMapper",
                            "TEST_ONLY menu fact provider","WorkingMemoryVersionService","ChatMemoryService","ConversationEventService"),
                    Map.of("modelHistory","actual Production: system + current user ONLY; no additional history",
                            "firstTurn","original user text + declared semantic seed + fixed retrieval -> real PRE/POST and renderer",
                            "geography","TEST_ONLY unique 福州市测试位置; C23 specified test mall floor B1",
                            "externalFacts","TEST_ONLY; no real merchant assertions or business database writes",
                            "scoring","all frozen MUST/MUST NOT core; evidence review required, missing checks stay UNVERIFIED")));
        }
        save(dir.resolve("plan.json"),plans);
        Map<String,Object> metadata=new LinkedHashMap<>();metadata.put("repositorySha",sha);metadata.put("dirtyWorkspace",dirty);
        metadata.put("model",properties.getModel());metadata.put("timeoutMs",properties.getTimeoutMs());metadata.put("runnerRetry",false);
        metadata.put("transportRetry",properties.getModelRetry());metadata.put("javaVersion",System.getProperty("java.version"));
        for(String file:List.of("src/main/java/com/hmdp/ai/v2/runtime/SemanticInterpreter.java",
                "src/test/java/com/hmdp/ai/evaluation/ControlledFourFixtures.java","src/test/java/com/hmdp/ai/evaluation/V2ControlledFourRealModelTest.java"))
            metadata.put(file+".sha256",V2RealModelPilotTest.sha256(Files.readAllBytes(Path.of(file))));
        save(dir.resolve("metadata.json"),metadata);
        var modelEvidence=new V2ModelCallEvidence(json);var observed=spy(client);
        doAnswer(i->modelEvidence.observe(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),()->{
            try{return (JsonNode)i.callRealMethod();}catch(RuntimeException e){throw e;}catch(Throwable e){throw new AssertionError(e);}
        },observed::lastTransportAttempts)).when(observed).chatCompletion(anyList(),anyList(),anyMap(),eq("V2_SEMANTIC_INTERPRETATION"));
        ReflectionTestUtils.setField(interpreter,"aiClient",observed);List<ScenarioResult> results=new ArrayList<>();
        try{
            for(Plan plan:plans){
                String id=plan.scenarioId();var semantic=spy(interpreter);var h=new V2PilotHarness(semantic,"test-four-"+run+"-"+id);
                doAnswer(i->{h.actualSemantic=(TurnSemantics)i.callRealMethod();return h.actualSemantic;}).when(semantic).interpret(anyString());
                Object meal=id.equals("C23")?ControlledFourFixtures.mainMeal():null;
                var factReads=ControlledFourFixtures.configure(h,id,true,meal);
                save(dir.resolve(id+"-fixture.json"),Map.of("label","TEST_ONLY", "firstSemanticSeed",ControlledFourFixtures.first(id),
                        "firstCandidates",ControlledFourFixtures.shops(id,false),"laterCandidates",ControlledFourFixtures.shops(id,true),
                        "factBranch",plan.factBranch(),"mainMealEvidence",meal==null?"UNKNOWN":meal));
                List<Map<String,Object>> turns=new ArrayList<>();
                for(int n=0;n<2;n++){
                    modelEvidence.calls.clear();factReads.clear();h.actualSemantic=null;
                    String message=originals.get(id).get(n);Map<String,Object> row=new LinkedHashMap<>();
                    row.put("turn",n+1);row.put("userMessage",message);row.put("semanticSource",n==0?"TEST_ONLY FIRST_TURN_PREPARATION":"REAL_MODEL_PRODUCTION");
                    row.put("before",h.persisted==null?json.createObjectNode():json.readTree(h.persisted.getMemoryJson()));long start=System.nanoTime();
                    try{
                        TurnEvidence t=n==0?h.turn(message,ControlledFourFixtures.first(id),ControlledFourFixtures.shops(id,false)):
                                h.turn(message,id.equals("C03")?ControlledFourFixtures.shops(id,true):List.of());
                        row.put("returned",true);row.put("evidence",t);
                    }catch(RuntimeException e){
                        row.put("returned",false);row.put("failureType",e.getClass().getName());row.put("failureMessage",Objects.toString(e.getMessage(),""));
                        row.put("failureCategory",e instanceof SemanticModelAvailabilityException?"MODEL_AVAILABILITY":e instanceof SemanticInterpretationException?"SEMANTIC_CONTRACT":"EXECUTION_OR_FIXTURE");
                        row.put("afterFailure",h.persisted==null?json.createObjectNode():json.readTree(h.persisted.getMemoryJson()));
                    }
                    row.put("durationMs",(System.nanoTime()-start)/1_000_000);row.put("modelCalls",List.copyOf(modelEvidence.calls));
                    row.put("semanticAttempts",semantic.takeEvaluationAttempts());row.put("commits",List.copyOf(h.commits));
                    row.put("runtimeEvents",List.copyOf(h.events));row.put("fixtureToolReads",List.copyOf(factReads));turns.add(row);
                    save(dir.resolve(id+"-turns.json"),turns);
                }
                TurnEvidence first=h.turns.getFirst();String task=first.workingMemory().path("activeTaskId").asText();
                Gate gate=displayGate(first,task,"V2-1",List.of(11L,22L,33L),Stage.PREPARATION);
                // 运行成功不等于业务成功。全部验收项留给对原始证据的逐项审核，不用 COMPLETED 代替业务断言。
                ScenarioResult result=finish(plan,gate,h.turns,List.of());results.add(result);
                save(dir.resolve(id+"-result.json"),result);write(json,dir.resolve("report.json"),report(plans,results));
            }
            save(dir.resolve("COMPLETED.json"),Map.of("scenarioCount",results.size(),"completedAt",java.time.Instant.now().toString()));
            assertEquals(4,results.size());assertEquals(4,report(plans,results).summary().ready());
        }finally{ReflectionTestUtils.setField(interpreter,"aiClient",client);}
    }
    static String git(String... args)throws Exception{var cmd=new ArrayList<String>();cmd.add("git");cmd.addAll(List.of(args));var p=new ProcessBuilder(cmd).start();String out=new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertEquals(0,p.waitFor());return out.trim();}
    void save(Path p,Object value)throws Exception{json.writerWithDefaultPrettyPrinter().writeValue(p.toFile(),value);}
}
