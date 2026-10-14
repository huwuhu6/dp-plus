package com.hmdp.ai.evaluation;
import com.fasterxml.jackson.databind.*;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.v2.runtime.*;
import com.hmdp.ai.v2.semantic.TurnSemantics;
import com.hmdp.ai.v2.grounding.V2LocationResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 两次首轮诊断，复用现有证据捕获；无定位/个人地点资料，不预置商户名单或评分框架。 */
@EnabledIfSystemProperty(named="v2.firstTurn.enabled",matches="true")
@SpringBootTest(classes=V2RealModelPilotTest.ModelConfiguration.class,webEnvironment=SpringBootTest.WebEnvironment.NONE)
class V2FirstTurnRealModelTest {
    @Autowired ObjectMapper json;@Autowired SemanticInterpreter interpreter;@Autowired OpenAiCompatibleClient client;@Autowired AiProperties properties;
    @Test void twoFirstTurnsOnce()throws Exception{
        assertEquals("deepseek-v4-flash-0731",properties.getModel());assertEquals(60000,properties.getTimeoutMs());
        String run=System.getProperty("v2.pilot.runId","");assertTrue(run.matches("[A-Za-z0-9_-]+"));
        Path dir=Path.of("target/eval-pilot",run);Files.createDirectories(dir);Files.writeString(dir.resolve("STARTED.json"),"{}",StandardOpenOption.CREATE_NEW);
        save(dir.resolve("metadata.json"),Map.of("repositorySha",V2ControlledFourRealModelTest.git("rev-parse","HEAD"),
                "dirtyWorkspace",!V2ControlledFourRealModelTest.git("status","--porcelain").isBlank(),"model",properties.getModel(),"timeoutMs",properties.getTimeoutMs(),
                "semanticSourceSha256",V2RealModelPilotTest.sha256(Files.readAllBytes(Path.of("src/main/java/com/hmdp/ai/v2/runtime/SemanticInterpreter.java"))),
                "geography","NO device location, NO known company profile, location provider disabled; no invented coordinates",
                "scope","actual semantic model + real deterministic V2 chain, isolated memory; not real merchant retrieval baseline"));
        var evidence=new V2ModelCallEvidence(json);var observed=spy(client);
        doAnswer(i->evidence.observe(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),()->{
            try{return (JsonNode)i.callRealMethod();}catch(RuntimeException e){throw e;}catch(Throwable e){throw new AssertionError(e);}
        },observed::lastTransportAttempts)).when(observed).chatCompletion(anyList(),anyList(),anyMap(),eq("V2_SEMANTIC_INTERPRETATION"));
        ReflectionTestUtils.setField(interpreter,"aiClient",observed);
        Map<String,String> inputs=new LinkedHashMap<>();inputs.put("company-noodles","公司附近吃碗面");inputs.put("C04",V2ControlledPilotTest.messages().get("C04").getFirst());
        save(dir.resolve("inputs.json"),inputs);
        try{
            for(var input:inputs.entrySet()){
                evidence.calls.clear();var semantic=spy(interpreter);var h=new V2PilotHarness(semantic,"test-first-"+run+"-"+input.getKey());
                ReflectionTestUtils.setField(ReflectionTestUtils.getField(h.chat,"v2ChatOrchestrator"),"locationResolver",new V2LocationResolver(null));
                doAnswer(i->{h.actualSemantic=(TurnSemantics)i.callRealMethod();return h.actualSemantic;}).when(semantic).interpret(anyString());
                Map<String,Object> result=new LinkedHashMap<>();result.put("userMessage",input.getValue());
                try{result.put("evidence",h.turn(input.getValue(),List.of()));result.put("returned",true);}
                catch(RuntimeException e){result.put("returned",false);result.put("failureType",e.getClass().getName());result.put("failureMessage",Objects.toString(e.getMessage(),""));}
                result.put("modelCalls",List.copyOf(evidence.calls));result.put("semanticAttempts",semantic.takeEvaluationAttempts());
                result.put("commits",List.copyOf(h.commits));result.put("events",List.copyOf(h.events));save(dir.resolve(input.getKey()+".json"),result);
            }
            save(dir.resolve("COMPLETED.json"),Map.of("runs",2));
        }finally{ReflectionTestUtils.setField(interpreter,"aiClient",client);}
    }
    void save(Path file,Object value)throws Exception{json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(),value);}
}
