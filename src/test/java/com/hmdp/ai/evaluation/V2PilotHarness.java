package com.hmdp.ai.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.*;
import com.hmdp.ai.entity.AiWorkingMemory;
import com.hmdp.ai.service.*;
import com.hmdp.ai.tool.*;
import com.hmdp.ai.v2.grounding.V2LocationResolver;
import com.hmdp.ai.v2.plan.ExecutionAction;
import com.hmdp.ai.v2.runtime.*;
import com.hmdp.ai.v2.semantic.TurnSemantics;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static com.hmdp.ai.evaluation.V2PilotEvaluation.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 仅 test scope：真实入口/POST 创建历史；模型、外部事实、持久化隔离，观察器不改变返回值。 */
final class V2PilotHarness {
    final ObjectMapper json=new ObjectMapper();
    final AiConversationEvaluationService assertions=new AiConversationEvaluationService();
    final List<TurnEvidence> turns=new ArrayList<>();
    final List<BoundaryCall> calls=new ArrayList<>();
    final List<Map<String,Object>> history=new ArrayList<>();
    final List<Map<String,Object>> assertionFailures=new ArrayList<>();
    final ChatOrchestrationService chat=new ChatOrchestrationService();
    final SemanticInterpreter interpreter=mock(SemanticInterpreter.class);
    final ShopRetrievalEngine retrieval=mock(ShopRetrievalEngine.class);
    AiWorkingMemory persisted;
    RuntimeException retrievalFailure;

    V2PilotHarness() throws Exception {
        ReflectionTestUtils.setField(assertions,"objectMapper",json);
        V2ChatOrchestrator runtime=new V2ChatOrchestrator();
        WorkingMemoryVersionService versions=mock(WorkingMemoryVersionService.class);
        when(versions.latest(anyString())).thenAnswer(i->persisted);
        when(versions.append(anyString(),nullable(Long.class),anyInt(),any(),any(),any(),anyMap())).thenAnswer(i->{
            int current=persisted==null?0:persisted.getVersion();
            if (i.<Integer>getArgument(2)!=current) throw new AssertionError("test memory version conflict");
            AiWorkingMemory row=new AiWorkingMemory();row.setId(1L);row.setVersion(current+1);row.setChatId(i.getArgument(0));
            row.setMemoryJson(json.writeValueAsString(i.getArgument(3)));persisted=row;return row;
        });
        ChatMemoryService chats=mock(ChatMemoryService.class);
        when(chats.resolveChatId(anyString())).thenAnswer(i->i.getArgument(0));
        when(chats.load(anyString())).thenAnswer(i->List.copyOf(history));
        doAnswer(i->{history.add(Map.of("role","user","content",i.getArgument(1)));history.add(Map.of("role","assistant","content",i.getArgument(2)));return null;})
                .when(chats).appendTurn(anyString(),anyString(),anyString(),anyString(),nullable(Long.class));
        LocationResolutionProvider location=mock(LocationResolutionProvider.class);
        when(location.isAvailable()).thenReturn(true);
        when(location.resolve(any(LocationResolutionRequest.class))).thenAnswer(i->{
            LocationResolutionRequest request=i.getArgument(0);ResolvedLocationCandidate candidate=new ResolvedLocationCandidate();
            candidate.setPoiId("TEST-"+request.getRawText());candidate.setCanonicalName(request.getRawText());candidate.setCity("福州市");
            candidate.setLatitude(26.0);candidate.setLongitude(119.0);candidate.setSource("CONTROLLED_TEST_FIXTURE");return List.of(candidate);
        });
        ShopMapper shops=mock(ShopMapper.class);
        when(shops.selectById(any())).thenAnswer(i->{Shop shop=new Shop();shop.setId(((Number)i.getArgument(0)).longValue());shop.setName("测试门店"+shop.getId());shop.setAddress("仅测试地址");shop.setAvgPrice(40L);shop.setOpenHours("10:00-22:00");return shop;});
        List<Map<String,Object>> toolReads=new ArrayList<>();
        GetShopDetailTool detail=spy(new GetShopDetailTool());ReflectionTestUtils.setField(detail,"shopMapper",shops);
        doAnswer(i->{AgentToolResult actual=(AgentToolResult)i.callRealMethod();
            toolReads.add(Map.of("tool","get_shop_detail","input",i.getArgument(0),"result",json.valueToTree(actual),"source","TEST_ONLY ShopMapper fixture"));
            return actual;}).when(detail).execute(anyMap());
        AgentToolRegistry registry=mock(AgentToolRegistry.class);when(registry.find("get_shop_detail")).thenReturn(detail);
        V2ActionHandler actions=spy(new V2ActionHandler());
        ReflectionTestUtils.setField(actions,"shopRetrievalEngine",retrieval);ReflectionTestUtils.setField(actions,"toolRegistry",registry);
        doAnswer(i->{
            ExecutionAction action=i.getArgument(0);
            toolReads.clear();
            // 旁路保存实际参数和原方法的返回，既不替换计划也不伪造 Grounding/执行结果。
            try {
                ExecutionObservation observation=(ExecutionObservation)i.callRealMethod();
                calls.add(observedCall(json,action,observation,"V2ActionHandler actual call; external facts are TEST_FIXTURE",toolReads));return observation;
            } catch(RuntimeException failure) {
                calls.add(observedFailure(json,action,failure,"actual external boundary exception",toolReads));
                throw failure; // 继续交给真实 executor 处理，观察器不吞异常或改变业务路径。
            }
        }).when(actions).execute(any());
        ReflectionTestUtils.setField(runtime,"semanticInterpreter",interpreter);ReflectionTestUtils.setField(runtime,"versions",versions);
        ReflectionTestUtils.setField(runtime,"chatMemoryService",chats);ReflectionTestUtils.setField(runtime,"conversationEventService",mock(ConversationEventService.class));
        ReflectionTestUtils.setField(runtime,"objectMapper",json);ReflectionTestUtils.setField(runtime,"executor",new StaticPlanExecutor());
        ReflectionTestUtils.setField(runtime,"actions",actions);ReflectionTestUtils.setField(runtime,"renderer",new V2ResponseRenderer());
        ReflectionTestUtils.setField(runtime,"shopMapper",shops);ReflectionTestUtils.setField(runtime,"locationResolver",new V2LocationResolver(location));
        ReflectionTestUtils.setField(chat,"v2ChatOrchestrator",runtime);
    }

    TurnEvidence turn(String originalMessage,TurnSemantics semantic,List<DecisionRecommendation> candidates) throws Exception {
        calls.clear();when(interpreter.interpret(originalMessage)).thenReturn(semantic);
        doAnswer(i->{if(retrievalFailure!=null)throw retrievalFailure;return new ShopRetrievalEngine.RetrievalResult(true,null,candidates,null);})
                .when(retrieval).retrieve(any(com.hmdp.ai.v2.plan.SearchSpec.class));
        ChatMessageRequest request=new ChatMessageRequest();request.setChatId("test-pilot");request.setMessage(originalMessage);
        ChatMessageResponse response=chat.chat(request,null,null);
        verify(interpreter).interpret(originalMessage);
        ConversationWorkingMemory memory=persisted==null?new ConversationWorkingMemory():json.readValue(persisted.getMemoryJson(),ConversationWorkingMemory.class);
        TurnEvidence evidence=capture(json,turns.size()+1,request,response,memory,semantic,List.copyOf(calls),true);turns.add(evidence);return evidence;
    }

    Map<String,Object> taskProjection(TurnEvidence turn,String id) {
        var task=task(turn,id);Map<String,Object> result=new LinkedHashMap<>();
        if (task==null) return result;
        result.put("taskId",task.path("taskId").asText());result.put("criteria",json.convertValue(task.path("v2Criteria"),Map.class));
        result.put("feedbackLedger",json.convertValue(task.path("v2FeedbackLedger"),List.class));
        result.put("selectedShopId",task.path("v2SelectedShopId").isNull()?null:task.path("v2SelectedShopId").longValue());
        result.put("rejectedShopIds",json.convertValue(task.path("v2RejectedShopIds"),List.class));return result;
    }
    boolean matches(Map<String,Object> expected,Map<String,Object> actual) {
        var failures=assertions.assertEvaluationProjection(turns.size(),expected,actual);
        assertionFailures.addAll(failures);return failures.isEmpty();
    }
}
