package com.hmdp.ai.evaluation;
import com.fasterxml.jackson.databind.*;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.v2.runtime.*;
import com.hmdp.ai.v2.semantic.*;
import com.hmdp.ai.dto.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.hmdp.ai.evaluation.V2PilotEvaluation.*;

/** 本轮运行配置；只组合既有Fixture和Harness，不修改任何业务对象或模型参数。 */
public class V2StageClosureRunner {
    static ObjectMapper json;static Path dir;
    static void save(String file,Object value)throws Exception{json.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(file+".json").toFile(),value);}
    public static void main(String[] args)throws Exception{
        dir=Path.of("target/eval-pilot",args[0]);Files.createDirectories(dir);Files.writeString(dir.resolve("STARTED.json"),"{}",StandardOpenOption.CREATE_NEW);
        try(var context=new SpringApplicationBuilder(V2RealModelPilotTest.ModelConfiguration.class).web(WebApplicationType.NONE).run()){
            json=context.getBean(ObjectMapper.class);var interpreter=context.getBean(SemanticInterpreter.class);var client=context.getBean(OpenAiCompatibleClient.class);var props=context.getBean(AiProperties.class);
            if(!props.isConfigured() || !props.getModel().equals("deepseek-v4-flash-0731") || props.getTimeoutMs()!=60000)throw new IllegalStateException("frozen model configuration unavailable");
            var ids=List.of("S02","S04","S06","S08","S09","S13","C03","C06","C15","C23");
            var originals=V2ControlledPilotTest.messages();
            save("metadata",Map.of("repositorySha",V2ControlledFourRealModelTest.git("rev-parse","HEAD"),"dirtyWorkspace",!V2ControlledFourRealModelTest.git("status","--porcelain").isBlank(),"model",props.getModel(),"timeoutMs",props.getTimeoutMs(),"retry",props.getModelRetry(),"scenarios",ids,"scope","TEST_ONLY controlled preparation; actual model on subsequent original turns; isolated persistence; not real retrieval E2E","runnerSourceSha256",V2RealModelPilotTest.sha256(Files.readAllBytes(Path.of("target/eval-pilot/v2-stage-closure/V2StageClosureRunner.java")))));
            var model=new V2ModelCallEvidence(json);var observed=spy(client);
            doAnswer(i->model.observe(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),()->{try{return (JsonNode)i.callRealMethod();}catch(RuntimeException e){throw e;}catch(Throwable e){throw new AssertionError(e);}},observed::lastTransportAttempts)).when(observed).chatCompletion(anyList(),anyList(),anyMap(),eq("V2_SEMANTIC_INTERPRETATION"));
            ReflectionTestUtils.setField(interpreter,"aiClient",observed);
            for(String id:ids){
                var semantic=spy(interpreter);var h=new V2PilotHarness(semantic,"test-stage-close-"+args[0]+"-"+id);
                doAnswer(i->{h.actualSemantic=(TurnSemantics)i.callRealMethod();return h.actualSemantic;}).when(semantic).interpret(anyString());
                boolean four=ControlledFourFixtures.IDS.contains(id);List<Map<String,Object>> factReads=four?ControlledFourFixtures.configure(h,id,true,id.equals("C23")?ControlledFourFixtures.mainMeal():null):new ArrayList<>();
                if(id.equals("S08"))h.deviceLocation=V2ControlledPilotTest.knownTestDevice();
                TurnSemantics seed;
                if(four)seed=ControlledFourFixtures.first(id);
                else{
                    String food=switch(id){case "S04"->"日料";case "S08"->"火锅";case "S09"->"餐饮";case "S13"->"早餐";case "C06"->"面";default->"牛排";};
                    String poi=switch(id){case "S04"->"大学城";case "S09"->"测试商场四楼";case "S13"->"地铁口";case "C06"->"公司附近";case "C15"->"国贸";default->"附近";};
                    DiningCriteria initial=ReflectionTestUtils.invokeMethod(V2ControlledPilotTest.class,"criteria",food,poi,id.equals("C06")?"今晚":null,id.equals("C06")?List.of("单人"):List.of());
                    if(id.equals("C15"))initial=new DiningCriteria(initial.location(),initial.cuisine(),new DiningCriteria.BudgetCriteria(null,java.math.BigDecimal.valueOf(200)),initial.distance(),null,initial.preferences());
                    seed=ReflectionTestUtils.invokeMethod(V2ControlledPilotTest.class,"sem",TaskDirective.START_NEW,initial,List.of(),List.of(new UserRequest.RecommendationRequest("recommend")));
                }
                var firstShops=four?ControlledFourFixtures.shops(id,false):V2ControlledPilotTest.shops(id,false);
                save(id+"-fixture",Map.of("label","TEST_ONLY EXISTING_FIXTURE_UNCHANGED","seed",seed,"firstShops",firstShops,"nextShops",four?ControlledFourFixtures.shops(id,true):V2ControlledPilotTest.shops(id,true),"branch",four?ControlledFourFixtures.branch(id):"existing six-pilot known identities/prices; menu/subjective facts unknown"));
                var rows=new ArrayList<Map<String,Object>>();boolean ready=false;
                for(int n=0;n<originals.get(id).size();n++){
                    model.calls.clear();factReads.clear();h.actualSemantic=null;Map<String,Object> row=new LinkedHashMap<>();row.put("turn",n+1);row.put("userMessage",originals.get(id).get(n));row.put("semanticSource",n==0?"TEST_ONLY_PREPARATION":"REAL_MODEL_PRODUCTION");row.put("before",h.persisted==null?json.createObjectNode():json.readTree(h.persisted.getMemoryJson()));
                    try{
                        TurnEvidence turn=n==0?h.turn(originals.get(id).get(n),seed,firstShops):h.turn(originals.get(id).get(n),four?(id.equals("C03")?ControlledFourFixtures.shops(id,true):List.of()):(id.equals("S13")||n==2?List.of():V2ControlledPilotTest.shops(id,true)));
                        row.put("returned",true);row.put("evidence",turn);
                        if(n==0){var task=turn.workingMemory().path("activeTaskId").asText();var gate=displayGate(turn,task,"V2-1",List.of(11L,22L,33L),Stage.PREPARATION);ready=gate.readiness()==Readiness.READY;row.put("gate",gate);}
                    }catch(RuntimeException e){row.put("returned",false);row.put("failureType",e.getClass().getName());row.put("failureCategory",e instanceof SemanticModelAvailabilityException?"MODEL_AVAILABILITY":e instanceof SemanticInterpretationException?"SEMANTIC_CONTRACT":"EXECUTION_OR_FIXTURE");}
                    row.put("semanticAttempts",semantic.takeEvaluationAttempts());row.put("modelCalls",List.copyOf(model.calls));row.put("commits",List.copyOf(h.commits));row.put("events",List.copyOf(h.events));row.put("fixtureToolReads",List.copyOf(factReads));row.put("after",h.persisted==null?json.createObjectNode():json.readTree(h.persisted.getMemoryJson()));rows.add(row);save(id+"-turns",rows);
                    if(n==0&&!ready)break;
                }
                save(id+"-execution",Map.of("ready",ready,"turnsRecorded",rows.size(),"originalTurns",originals.get(id).size()));
            }
            save("COMPLETED",Map.of("scenarios",ids.size(),"runnerRetry",false));
        }
    }
}
