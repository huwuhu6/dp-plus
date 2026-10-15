package com.hmdp.ai.evaluation;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.*;
import com.hmdp.HmDianPingApplication;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.*;
import com.hmdp.ai.entity.*;
import com.hmdp.ai.mapper.*;
import com.hmdp.ai.service.*;
import com.hmdp.ai.v2.grounding.V2LocationResolver;
import com.hmdp.ai.v2.plan.*;
import com.hmdp.ai.v2.runtime.*;
import com.hmdp.ai.v2.semantic.*;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 一次只读检索诊断：沿用隔离会话，所有商户、地理和模型边界均实际调用，绝不写业务数据。 */
@EnabledIfSystemProperty(named="v2.retrievalDiagnosis.enabled",matches="true")
@SpringBootTest(classes=HmDianPingApplication.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,
        properties={"spring.flyway.enabled=false","ai.profile-rebuild.enabled=false","ai.vector-sync.enabled=false"})
class V2RealRetrievalDiagnosisTest {
    @Autowired ObjectMapper json;
    @Autowired SemanticInterpreter interpreter;
    @Autowired OpenAiCompatibleClient client;
    @Autowired AiProperties properties;
    @Autowired ShopMapper shops;
    @Autowired AiShopProfileMapper profiles;
    @Autowired AiReviewDocumentMapper reviews;
    @Autowired ShopRetrievalEngine productionEngine;
    @Autowired V2ActionHandler productionActions;
    @Autowired LocationResolutionProvider locations;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test void threeReadOnlyRequestsOnce() throws Exception {
        String run=System.getProperty("v2.pilot.runId","");assertTrue(run.matches("[A-Za-z0-9_-]+"));
        Path dir=Path.of("target/eval-pilot",run);Files.createDirectories(dir);
        Files.writeString(dir.resolve("STARTED.json"),"{}",StandardOpenOption.CREATE_NEW);
        boolean replay=Boolean.getBoolean("v2.retrievalDiagnosis.replay");
        boolean singleCuisine=Boolean.getBoolean("v2.retrievalDiagnosis.singleCuisine");
        save(dir,"metadata",Map.of("repositorySha",V2ControlledFourRealModelTest.git("rev-parse","HEAD"),
                "model",properties.getModel(),"timeoutMs",properties.getTimeoutMs(),"persistence","isolated test sessions; real DB SELECT only",
                "locationAuthorization",singleCuisine?"no device location; real POI from original user message":"user supplied 福州大学旗山校区附近; real geocoding, not measured device GPS",
                "mode",replay?"archived validated semantics replay; zero new model calls":"real model once"));
        try {
            var rows=shops.selectList(new QueryWrapper<>());
            var p=profiles.selectList(new QueryWrapper<>());
            Map<String,Long> cities=new TreeMap<>(),cuisines=new TreeMap<>();
            rows.forEach(s->cities.merge(Objects.toString(s.getCity(),"UNKNOWN"),1L,Long::sum));
            p.forEach(s->cuisines.merge(Objects.toString(s.getCuisine(),"UNKNOWN"),1L,Long::sum));
            save(dir,"inventory",Map.of("shops",rows.size(),"profiles",p.size(),"cities",cities,"profileCuisines",cuisines,
                    "sampleShopNames",rows.stream().limit(8).map(Shop::getName).toList(),"provenance","existing DB records; real-world freshness/authenticity not independently verified",
                    "shopsWithoutProfile",rows.stream().filter(s->p.stream().noneMatch(x->Objects.equals(x.getShopId(),s.getId()))).count(),
                    "shopsWithoutCoordinates",rows.stream().filter(s->s.getX()==null||s.getY()==null).count()));
        } catch(RuntimeException e) {
            save(dir,"inventory",Map.of("status","DEPENDENCY_BLOCKED","dependency","MYSQL","errorType",e.getClass().getName()));
        }
        auditFuzhou(dir);
        if(Boolean.getBoolean("v2.retrievalDiagnosis.auditOnly")){save(dir,"COMPLETED",Map.of("scope","MYSQL read-only audit; zero model calls"));return;}
        var model=new V2ModelCallEvidence(json);var observedClient=spy(client);
        doAnswer(i->model.observe(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),()->{
            try{return (JsonNode)i.callRealMethod();}catch(RuntimeException e){throw e;}catch(Throwable e){throw new AssertionError(e);}
        },observedClient::lastTransportAttempts)).when(observedClient).chatCompletion(anyList(),anyList(),anyMap(),eq("V2_SEMANTIC_INTERPRETATION"));
        ReflectionTestUtils.setField(interpreter,"aiClient",observedClient);
        ChatLocationInput authorized=null;
        if(!singleCuisine)try {
            LocationResolutionContext context=new LocationResolutionContext();context.setActiveCity("福州市");
            var candidates=locations.resolve(new LocationResolutionRequest("福州大学旗山校区",context,"POI"));
            save(dir,"authorized-location",Map.of("providerAvailable",locations.isAvailable(),"candidates",candidates,
                    "authorization","user-confirmed named place, not actual device measurement"));
            var resolution=new V2LocationResolver(locations).resolve(new DiningCriteria(new DiningCriteria.LocationCriteria("福州市",null,"福州大学旗山校区"),
                    null,null,null,null,DiningCriteria.SemanticPreferences.empty()),null,null);
            save(dir,"authorized-location-resolution",resolution);
            if(resolution instanceof V2LocationResolver.Resolution.Resolved resolved){
                authorized=new ChatLocationInput();authorized.setLatitude(resolved.anchor().latitude());
                authorized.setLongitude(resolved.anchor().longitude());authorized.setSource("USER_CONFIRMED_POI_GEOCODE");
            }
        } catch(RuntimeException e){save(dir,"authorized-location",Map.of("status","DEPENDENCY_BLOCKED","errorType",e.getClass().getName()));}
        Map<String,String> inputs=new LinkedHashMap<>();inputs.put("A","在福州市三坊七巷附近找面馆，人均50元以内。");
        inputs.put("B","附近找餐厅");inputs.put("C-S03",V2ControlledPilotTest.messages().get("S03").getFirst());save(dir,"inputs",inputs);
        if(singleCuisine){inputs.clear();inputs.put("first-food","福州市三坊七巷附近找面馆，人均50以内");save(dir,"inputs",inputs);}
        try {
            for(var input:inputs.entrySet()) {
                model.calls.clear();var semantic=spy(interpreter);var h=new V2PilotHarness(semantic,"test-retrieval-"+run+"-"+input.getKey());
                doAnswer(i->{h.actualSemantic=(TurnSemantics)i.callRealMethod();return h.actualSemantic;}).when(semantic).interpret(anyString());
                if(replay){
                    Path source=Path.of("target/eval-pilot",System.getProperty("v2.retrievalDiagnosis.sourceRun"),input.getKey()+".json");
                    JsonNode attempts=json.readTree(source.toFile()).path("semanticAttempts");JsonNode last=attempts.get(attempts.size()-1);
                    assertEquals("VALID",last.path("validationResult").asText());
                    TurnSemantics captured=ReflectionTestUtils.invokeMethod(interpreter,"normalizeLocationProvenance",input.getValue(),
                            ReflectionTestUtils.invokeMethod(interpreter,"normalizeDeterministicBudget",input.getValue(),interpreter.parse(last.path("effectiveStructuredOutput"))));
                    // JSON回读会将50.0与BigDecimal 5E+1保存为不同节点类型；只按数值比较，不放宽字段/数组语义。
                    assertTrue(last.path("finalSemantics").equals((a,b)->a.isNumber()&&b.isNumber()?a.decimalValue().compareTo(b.decimalValue()):a.equals(b)?0:1,
                            json.valueToTree(captured)),"replay must exactly preserve previously validated semantics");
                    doAnswer(i->{h.actualSemantic=captured;return captured;}).when(semantic).interpret(input.getValue());
                }
                if(input.getKey().equals("B"))h.deviceLocation=authorized;
                Object runtime=ReflectionTestUtils.getField(h.chat,"v2ChatOrchestrator");
                List<Map<String,Object>> geography=new ArrayList<>();var observedLocations=spy(locations);
                doAnswer(i->{var result=i.callRealMethod();geography.add(Map.of("request",i.getArgument(0),"candidates",result));return result;})
                        .when(observedLocations).resolve(any(LocationResolutionRequest.class));
                ReflectionTestUtils.setField(runtime,"shopMapper",shops);ReflectionTestUtils.setField(runtime,"locationResolver",new V2LocationResolver(observedLocations));
                List<Map<String,Object>> searches=new ArrayList<>();var engine=spy(productionEngine);
                doAnswer(i->{SearchSpec spec=i.getArgument(0);Map<String,Object> trace=new LinkedHashMap<>();trace.put("searchSpec",spec);searches.add(trace);
                    try {var result=(ShopRetrievalEngine.RetrievalResult)i.callRealMethod();trace.put("result",result);return result;}
                    catch(RuntimeException e){trace.put("status","DEPENDENCY_BLOCKED");trace.put("errorType",e.getClass().getName());throw e;}
                }).when(engine).retrieve(any(SearchSpec.class));
                var observedShops=spy(shops);
                doAnswer(i->{var q=i.getArgument(0);List<Shop> result=(List<Shop>)i.callRealMethod();
                    Map<String,Object> trace=searches.getLast();trace.put("sqlWhere",((QueryWrapper<?>)q).getSqlSegment());
                    trace.put("sqlParameters",new LinkedHashMap<>(((QueryWrapper<?>)q).getParamNameValuePairs()));
                    trace.put("mysqlCandidateIds",result.stream().map(Shop::getId).toList());
                    SearchSpec spec=(SearchSpec)trace.get("searchSpec");trace.put("predicateCounts",predicateCounts(spec,result));return result;
                }).when(observedShops).selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
                ReflectionTestUtils.setField(engine,"shopMapper",observedShops);
                Object realRecall=ReflectionTestUtils.getField(productionEngine,"semanticShopRetriever");
                if(realRecall!=null){var recall=spy((SemanticShopRetriever)realRecall);
                    doAnswer(i->{var result=i.callRealMethod();searches.getLast().put("milvusRecall",Map.of("query",i.getArgument(0),"whitelist",((List<Shop>)i.getArgument(1)).stream().map(Shop::getId).toList(),"result",result));return result;})
                            .when(recall).recall(anyString(),anyList(),anyMap(),anyMap());ReflectionTestUtils.setField(engine,"semanticShopRetriever",recall);}
                var actions=spy(productionActions);ReflectionTestUtils.setField(actions,"shopRetrievalEngine",engine);
                ReflectionTestUtils.setField(runtime,"actions",actions);
                Map<String,Object> out=new LinkedHashMap<>();out.put("userMessage",input.getValue());out.put("requestLocation",h.deviceLocation);
                if(input.getKey().equals("B")&&authorized==null)out.put("requestedDeviceExperiment","DEPENDENCY_BLOCKED: no uniquely resolved authorized coordinates; no invented GPS");
                try{out.put("evidence",h.turn(input.getValue(),List.of()));out.put("returned",true);}
                catch(RuntimeException e){out.put("returned",false);out.put("failureType",e.getClass().getName());}
                out.put("geography",geography);
                if(replay && !input.getKey().equals("B")){
                    var cuisine=input.getKey().equals("A")?"面食":"酸菜鱼";
                    var probe=new SearchSpec(0,"read-only-coverage-probe",ExecutionAction.SearchKind.RECOMMENDATIONS,3,
                            new DiningCriteria(new DiningCriteria.LocationCriteria("福州市",null,null),new DiningCriteria.CuisineCriteria(cuisine,List.of()),null,null,null,DiningCriteria.SemanticPreferences.empty()),
                            List.of(),Set.of(),null,new SearchAnchor(null,null,null,null,null,"福州市",null,SearchAnchor.AnchorSource.NAMED_LOCATION));
                    out.put("coverageProbeScope","separate citywide production query; not original model output or end-to-end success");
                    out.put("coverageProbe",engine.retrieve(probe));
                }
                out.put("searches",searches);out.put("modelCalls",List.copyOf(model.calls));out.put("semanticAttempts",semantic.takeEvaluationAttempts());
                out.put("commits",h.commits);out.put("events",h.events);save(dir,input.getKey(),out);
            }
            save(dir,"COMPLETED",Map.of("requests",inputs.size()));
        }finally{ReflectionTestUtils.setField(interpreter,"aiClient",client);}
    }
    /** 独立读取数据库类别与描述证据，不回填到模型、搜索条件或商户画像。 */
    void auditFuzhou(Path dir)throws Exception {
        String scope="s.city IN ('福州市','福州')";
        var rows=jdbc.queryForList("SELECT s.id,s.name,s.type_id,t.name AS category,s.district,s.avg_price,p.id AS profile_id,p.cuisine,p.scene_tags,p.ambience_tags,p.summary FROM tbl_shop s LEFT JOIN tbl_shop_type t ON t.id=s.type_id LEFT JOIN tbl_ai_shop_profile p ON p.shop_id=s.id WHERE "+scope);
        var reviewRows=jdbc.queryForList("SELECT d.shop_id,d.content FROM tbl_ai_review_document d JOIN tbl_shop s ON s.id=d.shop_id WHERE "+scope);
        Map<Long,String> reviewTexts=new HashMap<>();reviewRows.forEach(r->reviewTexts.merge(((Number)r.get("shop_id")).longValue(),Objects.toString(r.get("content"),""),(a,b)->a+"\n"+b));
        Map<String,Long> cuisineDist=new TreeMap<>(),categoryDist=new TreeMap<>();
        rows.forEach(r->{cuisineDist.merge(Objects.toString(r.get("cuisine"),"NULL"),1L,Long::sum);categoryDist.merge(Objects.toString(r.get("category"),"NULL"),1L,Long::sum);});
        Map<String,Object> terms=new LinkedHashMap<>();
        for(String term:List.of("面馆","面食","粉面","米线","酸菜鱼","炸鸡","韩式炸鸡")){
            List<Long> name=new ArrayList<>(),category=new ArrayList<>(),cuisine=new ArrayList<>(),description=new ArrayList<>(),review=new ArrayList<>();
            List<Map<String,Object>> evidence=new ArrayList<>();
            for(var row:rows){long id=((Number)row.get("id")).longValue();var p=new AiShopProfile();p.setCuisine((String)row.get("cuisine"));
                if(Objects.toString(row.get("name"),"").contains(term))name.add(id);
                if(Objects.toString(row.get("category"),"").contains(term))category.add(id);
                if(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(productionEngine,"matchesCuisine",p,term)))cuisine.add(id);
                if(List.of("scene_tags","ambience_tags","summary").stream().anyMatch(k->Objects.toString(row.get(k),"").contains(term)))description.add(id);
                if(reviewTexts.getOrDefault(id,"").contains(term))review.add(id);
                if(name.contains(id)||category.contains(id)||cuisine.contains(id)||description.contains(id)||review.contains(id)){
                    Map<String,Object> item=new LinkedHashMap<>(row);item.put("reviewMentions",review.contains(id));
                    item.put("reviewEvidence",reviewRows.stream().filter(r->((Number)r.get("shop_id")).longValue()==id && Objects.toString(r.get("content"),"").contains(term)).toList());
                    evidence.add(item);
                }
            }
            terms.put(term,Map.of("nameIds",name,"categoryIds",category,"productionCuisineMatchIds",cuisine,"profileDescriptionIds",description,"reviewMentionIds",review,"evidenceRows",evidence));
        }
        save(dir,"fuzhou-category-audit",Map.of("shops",rows.size(),"withProfile",rows.stream().filter(r->r.get("profile_id")!=null).count(),
                "cuisineDistribution",cuisineDist,"shopCategoryDistribution",categoryDist,"termEvidence",terms,
                "missingProfiles",rows.stream().filter(r->r.get("profile_id")==null).toList(),"withDescription",rows.stream().filter(r->!Objects.toString(r.get("summary"),"").isBlank()).count(),
                "withSceneTags",rows.stream().filter(r->!Objects.toString(r.get("scene_tags"),"").isBlank()).count(),
                "withAmbienceTags",rows.stream().filter(r->!Objects.toString(r.get("ambience_tags"),"").isBlank()).count(),
                "scope","existing DB SELECT only; names/review mentions are clues, not verified current menus"));
    }
    Map<String,Object> predicateCounts(SearchSpec spec,List<Shop> initial){
        Map<Long,AiShopProfile> p=new HashMap<>();Map<Long,List<AiReviewDocument>> r=new HashMap<>();
        if(!initial.isEmpty()){
            var ids=initial.stream().map(Shop::getId).toList();
            profiles.selectList(new QueryWrapper<AiShopProfile>().in("shop_id",ids)).forEach(x->p.putIfAbsent(x.getShopId(),x));
            reviews.selectList(new QueryWrapper<AiReviewDocument>().in("shop_id",ids)).forEach(x->r.computeIfAbsent(x.getShopId(),k->new ArrayList<>()).add(x));
        }
        var c=spec.criteria();Map<String,Object> counts=new LinkedHashMap<>();counts.put("method","read-only replay of exact production hardMatch predicates; actual SQL is recorded separately");
        List<Shop> current=initial;counts.put("afterSql",current.size());
        for(int stage=0;stage<4;stage++){
            DiningCriteria partial=new DiningCriteria(c.location(),c.cuisine(),c.budget(),stage>=1?c.distance():null,
                    stage>=3?c.diningTime():null,stage>=2?c.preferences():DiningCriteria.SemanticPreferences.empty());
            current=initial.stream().filter(s->Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(productionEngine,"hardMatch",s,p.get(s.getId()),r.get(s.getId()),partial,spec.searchAnchor()))).toList();
            counts.put(List.of("afterCuisine","afterUserDistance","afterMustEvidence","afterOpeningTime").get(stage),current.size());
        }
        for(double radius:new double[]{3D,5D})counts.put("afterRadius"+radius,current.stream().filter(s->spec.searchAnchor()!=null && spec.searchAnchor().latitude()!=null &&
                (Double)ReflectionTestUtils.invokeMethod(productionEngine,"distance",spec.searchAnchor().latitude(),spec.searchAnchor().longitude(),s.getY(),s.getX())<=radius).count());
        counts.put("note","3/5km counts are diagnostics only; actual applied policy and totals are in retrieval metrics");return counts;
    }
    void save(Path dir,String name,Object value)throws Exception{json.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(name+".json").toFile(),value);}
}
