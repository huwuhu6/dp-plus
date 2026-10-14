package com.hmdp.ai.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.v2.semantic.*;
import com.hmdp.ai.v2.reducer.V2FeedbackEntry;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import static com.hmdp.ai.evaluation.V2PilotEvaluation.*;
import static org.junit.jupiter.api.Assertions.*;

/** 原话真实入口、语义模型替身；真实业务失败保留在文件，不把测试通过称为业务全通过。 */
class V2ControlledPilotTest {
    private static final String FREEZE="156b81c35e3bfc5c87813b9f5f6a6c46b4ab0b88";
    private static final Map<String,List<String>> MESSAGES=messages();
    private static final List<String> IDS=List.of("S04","S08","S09","S13","C06","C15");

    @Test void runSixControlledScenariosAndWriteVersionedEvidence() throws Exception {
        List<Plan> plans=new ArrayList<>(); List<ScenarioResult> results=new ArrayList<>();
        for(String id:IDS) plans.add(plan(id));
        Files.createDirectories(Path.of("target/eval-pilot"));
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("target/eval-pilot/plan-v1.json").toFile(),plans);
        for (Plan plan:plans) {
            String id=plan.scenarioId();V2PilotHarness h=new V2PilotHarness();
            String food=switch(id){case "S04"->"日料";case "S08"->"火锅";case "S09"->"餐饮";case "S13"->"早餐";case "C06"->"面";default->"牛排";};
            String poi=switch(id){case "S04"->"大学城";case "S09"->"测试商场四楼";case "S13"->"地铁口";case "C06"->"公司附近";case "C15"->"国贸";default->"附近";};
            DiningCriteria initial=criteria(food,poi,id.equals("C06")?"今晚":null,id.equals("C06")?List.of("单人"):List.of());
            if(id.equals("C15")) initial=new DiningCriteria(initial.location(),initial.cuisine(),new DiningCriteria.BudgetCriteria(null,BigDecimal.valueOf(200)),initial.distance(),null,initial.preferences());
            TurnEvidence first=h.turn(MESSAGES.get(id).getFirst(),sem(TaskDirective.START_NEW,initial,List.of(),recommend()),shops(id,false));
            String oldTask=first.workingMemory().path("activeTaskId").asText();
            Gate gate=displayGate(first,oldTask,"V2-1",List.of(11L,22L,33L),Stage.PREPARATION);
            assertEquals(Readiness.READY,gate.readiness(),id);
            Map<String,Boolean> checked=new LinkedHashMap<>(); Map<String,String> reasons=new HashMap<>();
            TurnEvidence last;
            switch(id) {
                case "S04" -> {
                    var changed=new DiningCriteria(initial.location(),initial.cuisine(),new DiningCriteria.BudgetCriteria(BigDecimal.valueOf(35),null),null,null,initial.preferences());
                    last=h.turn(MESSAGES.get(id).get(1),sem(TaskDirective.CONTINUE,changed,List.of(),recommend()),shops(id,true));
                    checked.put("M1",h.matches(Map.of("criteria.cuisine.include","日料","criteria.location.poi","大学城","criteria.budget.softTarget",35),h.taskProjection(last,oldTask)));
                    checked.put("M2",last.fullResponse().path("decision").path("recommendations").get(0).path("avgPrice").asInt()==40);
                    reasons.put("M2","test-only approved 40/person option vs initial 120/150/180; no edge tolerance or universal exact budget value asserted");
                }
                case "S08" -> {
                    last=h.turn(MESSAGES.get(id).get(1),sem(TaskDirective.CONTINUE,criteria("寿司",poi,null,List.of()),List.of(),recommend()),shops(id,true));
                    checked.put("M1",h.matches(Map.of("criteria.cuisine.include","寿司","criteria.preferences.values",Map.of("empty",true)),h.taskProjection(last,oldTask)));
                    checked.put("M2",h.matches(Map.of("criteria.location",Map.of("equals",((Map<?,?>)h.taskProjection(first,oldTask).get("criteria")).get("location"))),h.taskProjection(last,oldTask)));
                }
                case "S09" -> {
                    var feedback=new EntityFeedback.BatchFeedback(new BatchRef(BatchRef.BatchSelector.CURRENT_VISIBLE),EntityFeedback.BatchPolarity.NEGATIVE,EntityFeedback.FeedbackAspect.UNSPECIFIED);
                    last=h.turn(MESSAGES.get(id).get(1),sem(TaskDirective.CONTINUE,null,List.of(feedback),List.of(new UserRequest.AlternativesRequest("alternatives",3))),shops(id,true));
                    var expectedLedger=h.json.convertValue(List.of(new V2FeedbackEntry(V2FeedbackEntry.Scope.BATCH,null,"V2-1",null,EntityFeedback.BatchPolarity.NEGATIVE,EntityFeedback.FeedbackAspect.UNSPECIFIED)),List.class);
                    checked.put("M1",disjoint(h,first,last)&&h.matches(Map.of("feedbackLedger",expectedLedger),h.taskProjection(last,oldTask)));
                    assertTrue(h.matches(Map.of("rejectedShopIds",List.of(),"criteria.cuisine.exclude",List.of()),h.taskProjection(last,oldTask)));
                    checked.put("F2",null);
                    reasons.put("F2","partial task-level ledger/exclusions verified; global/permanent behavior not observed, full item remains UNVERIFIED");
                }
                case "S13" -> {
                    last=h.turn(MESSAGES.get(id).get(1),sem(TaskDirective.CONTINUE,null,List.of(),List.of(new UserRequest.FactQueryRequest("address",new EntityReference.FocusedEntityRef(),UserRequest.FactType.DETAIL))),List.of());
                    checked.put("M1",identityClarification(last));
                    checked.put("F1",h.matches(Map.of("selectedShopId",Map.of("null",true)),h.taskProjection(last,oldTask))&&last.executionCalls().isEmpty());
                }
                case "C06" -> {
                    var feedback=new EntityFeedback.EntityFeedbackItem(new EntityReference.OrdinalRef(2),EntityFeedback.FeedbackKind.POSITIVE,EntityFeedback.FeedbackAspect.UNSPECIFIED);
                    last=h.turn(MESSAGES.get(id).get(1),sem(TaskDirective.START_NEW,criteria("杭帮菜", "南站", "明天中午",List.of("6人聚餐","上档次")),List.of(feedback),recommend()),shops(id,true));
                    checked.put("M1",positiveOldFeedback(last,oldTask));
                    checked.put("M3",arrangementIsolation(h,first,last,oldTask));
                    assertTrue(positiveOldFeedback(last,oldTask));
                    assertEquals(Boolean.TRUE,arrangementIsolation(h,first,last,oldTask));
                    reasons.put("M1","real PRE grounds feedback against pre-turn task and atomically persists it with the new arrangement");
                    reasons.put("M3","old criteria retained and independent new arrangement established; missing arrangement still remains UNVERIFIED in its separate negative test");
                }
                default -> {
                    DiningCriteria changed=new DiningCriteria(initial.location(),initial.cuisine(),initial.budget(),null,null,
                            new DiningCriteria.SemanticPreferences(List.of(new DiningCriteria.SemanticPreference("小众",DiningCriteria.Importance.PREFER),new DiningCriteria.SemanticPreference("非大商场连锁",DiningCriteria.Importance.MUST)),List.of()));
                    TurnEvidence second=h.turn(MESSAGES.get(id).get(1),sem(TaskDirective.CONTINUE,changed,List.of(),List.of(new UserRequest.AlternativesRequest("alternatives",2))),shops(id,true));
                    Gate secondGate=displayGate(second,oldTask,"V2-2",List.of(44L,55L),Stage.PREPARATION);
                    assertEquals(Readiness.READY,secondGate.readiness());
                    gate=new Gate(Readiness.READY,Stage.PREPARATION,List.of("turns/1/fullResponse","turns/1/workingMemory","turns/2/fullResponse","turns/2/workingMemory"),"two actual POST batches agree with distinct displays; first B=22, current D/E=44/55");
                    last=h.turn(MESSAGES.get(id).get(2),historicalQuery(new EntityReference.NamedEntityRef("澳洲牛排馆")),List.of());
                    checked.put("M2",boundToFirstB(h,last));
                    checked.put("M4",h.matches(Map.of("criteria",Map.of("equals",h.taskProjection(second,oldTask).get("criteria")),"selectedShopId",Map.of("null",true)),h.taskProjection(last,oldTask)));
                    assertTrue(boundToFirstB(h,last));
                    reasons.put("M2","historical B identity independently grounded and actual detail tool receives shopId 22");
                }
            }
            List<ItemResult> items=new ArrayList<>();
            for(ItemPlan item:plan.items()) {
                String key=item.itemId().substring(id.length()+1);
                items.add(check(item,gate,checked.get(key),checked.containsKey(key)?List.of("turns/"+last.turn()+"/fullResponse","turns/"+last.turn()+"/workingMemory","turns/"+last.turn()+"/executionCalls"):List.of(),
                        reasons.getOrDefault(key,checked.containsKey(key)?"controlled predicate executed; see actual snapshots/calls":"not automated in pilot; human review required")
                                +(Boolean.FALSE.equals(checked.get(key))?"; predicate failures="+h.json.writeValueAsString(h.assertionFailures):""),checked.get(key)!=null?Stage.BUSINESS_TURN:Stage.OBSERVATION));
            }
            results.add(finish(plan,gate,h.turns,items));
        }
        Report report=report(plans,results);
        write(new com.fasterxml.jackson.databind.ObjectMapper(),Path.of("target/eval-pilot/controlled-production-fixed-v1.json"),report);
        assertEquals(6,report.summary().planned());assertEquals(6,report.summary().ready());
        assertEquals(0,report.summary().corePassed());assertEquals(0,report.summary().fullyAdjudicated());
        assertEquals(0,report.summary().businessFailed());
        assertTrue(report.scenarios().stream().allMatch(s->s.items().stream().anyMatch(i->i.status()==Status.UNVERIFIED)));
        write(new com.fasterxml.jackson.databind.ObjectMapper(),Path.of("target/eval-pilot/controlled-production-fixed-v1.json"),report);
    }

    @Test void cancelledHotpotStillEffectiveFailsActualPredicate() throws Exception {
        V2PilotHarness h=new V2PilotHarness();
        TurnEvidence first=h.turn(MESSAGES.get("S08").get(0),sem(TaskDirective.START_NEW,criteria("火锅","附近",null,List.of()),List.of(),recommend()),shops("S08",false));
        String taskId=first.workingMemory().path("activeTaskId").asText();
        // 错误解释仅新增寿司偏好，未取消旧火锅；真实 reducer/compiler 使用旧 cuisine。
        var wrong=new RequirementChange.CriteriaPatch(new DiningCriteriaPatch(null,null,null,null,null,new DiningCriteria.SemanticPreferences(List.of(new DiningCriteria.SemanticPreference("寿司",DiningCriteria.Importance.PREFER)),List.of())),Set.of());
        TurnEvidence actual=h.turn(MESSAGES.get("S08").get(1),new TurnSemantics(TaskDirective.CONTINUE,List.of(wrong),List.of(),recommend(),List.of()),shops("S08",true));
        assertFalse(h.matches(Map.of("criteria.cuisine.include","寿司"),h.taskProjection(actual,taskId)));
        assertEquals("火锅",actual.executionCalls().getFirst().action().path("spec").path("criteria").path("cuisine").path("include").asText());
    }

    @Test void repeatBatchIsFilteredButCannotPassOtherChoicesCheck() throws Exception {
        V2PilotHarness h=new V2PilotHarness();
        TurnEvidence first=h.turn(MESSAGES.get("S09").get(0),sem(TaskDirective.START_NEW,criteria("餐饮","商场四楼",null,List.of()),List.of(),recommend()),shops("S09",false));
        TurnEvidence actual=h.turn(MESSAGES.get("S09").get(1),sem(TaskDirective.CONTINUE,null,List.of(),List.of(new UserRequest.AlternativesRequest("alternatives",3))),shops("S09",false));
        assertFalse(disjoint(h,first,actual));
        assertTrue(actual.fullResponse().path("decision").path("recommendations").isEmpty());
    }

    @Test void guessingFirstShopDoesNotPassS13Clarification() throws Exception {
        V2PilotHarness h=new V2PilotHarness();
        h.turn(MESSAGES.get("S13").get(0),sem(TaskDirective.START_NEW,criteria("早餐","地铁口",null,List.of()),List.of(),recommend()),shops("S13",false));
        TurnEvidence actual=h.turn(MESSAGES.get("S13").get(1),sem(TaskDirective.CONTINUE,null,List.of(),List.of(new UserRequest.SelectRequest("choose",new EntityReference.OrdinalRef(1)),new UserRequest.FactQueryRequest("address",new EntityReference.OrdinalRef(1),UserRequest.FactType.DETAIL))),List.of());
        assertTrue(identityClarification(actual));assertTrue(actual.executionCalls().isEmpty());
        String id=actual.workingMemory().path("activeTaskId").asText();
        assertTrue(h.matches(Map.of("selectedShopId",Map.of("null",true)),h.taskProjection(actual,id)));
        // 真正选择/查询第一家不构成澄清：观察器仍能拒绝已执行的选择结果冒充澄清。
        TurnEvidence selected=h.turn("就去第一家，把地址发我",sem(TaskDirective.CONTINUE,null,List.of(),List.of(new UserRequest.SelectRequest("choose",new EntityReference.OrdinalRef(1)),new UserRequest.FactQueryRequest("address",new EntityReference.OrdinalRef(1),UserRequest.FactType.DETAIL))),List.of());
        assertFalse(identityClarification(selected));assertFalse(selected.executionCalls().isEmpty());
        assertFalse(h.matches(Map.of("selectedShopId",Map.of("null",true)),h.taskProjection(selected,id)));
    }

    @Test void independentArrangementPassesIsolationWhileContinuingIntoOldTaskFails() throws Exception {
        for(TaskDirective directive:List.of(TaskDirective.START_NEW,TaskDirective.CONTINUE)) {
            V2PilotHarness h=new V2PilotHarness();
            TurnEvidence first=h.turn(MESSAGES.get("C06").get(0),sem(TaskDirective.START_NEW,criteria("面","公司附近","今晚",List.of("单人")),List.of(),recommend()),shops("C06",false));
            String old=first.workingMemory().path("activeTaskId").asText();
            TurnEvidence actual=h.turn(MESSAGES.get("C06").get(1),sem(directive,criteria("杭帮菜","南站","明天中午",List.of("6人聚餐","上档次")),List.of(),recommend()),shops("C06",true));
            assertEquals(directive==TaskDirective.START_NEW,isolated(h,first,actual,old),()->h.assertionFailures.toString()+" actual="+actual.workingMemory());
            assertEquals(directive==TaskDirective.START_NEW,arrangementIsolation(h,first,actual,old));
            // 此控制组只证明隔离谓词；省略反馈不代表完整 C06 通过。
            assertFalse(positiveOldFeedback(actual,old));
        }
    }

    @Test void historicalWrongShopFailsDespiteNonemptyGroundingAndExecutedQuery() throws Exception {
        V2PilotHarness h=new V2PilotHarness();
        h.turn(MESSAGES.get("C15").get(0),sem(TaskDirective.START_NEW,criteria("牛排","国贸",null,List.of()),List.of(),recommend()),shops("C15",false));
        h.turn(MESSAGES.get("C15").get(1),sem(TaskDirective.CONTINUE,null,List.of(),List.of(new UserRequest.AlternativesRequest("alternatives",2))),shops("C15",true));
        // 身份谓词负例：真实执行当前 E，再要求它证明历史 B，必须失败。
        // 原 C15 原话的错误序号现在被 Production 防护纠正，另有专门原话回归验证。
        TurnEvidence actual=h.turn("当前第二家地址和详情",historicalQuery(new EntityReference.OrdinalRef(2)),List.of());
        assertEquals(2,actual.executionCalls().size());
        assertEquals(55,actual.executionCalls().getFirst().action().path("shop").path("shopId").asInt());
        assertEquals("address",actual.executionCalls().getFirst().action().path("requestId").asText());
        assertEquals(55,actual.executionCalls().getFirst().toolReads().get(0).path("result").path("facts").path("shop").path("id").asInt());
        assertEquals("TEST_ONLY ShopMapper fixture",actual.executionCalls().getFirst().toolReads().get(0).path("source").asText());
        assertFalse(boundToFirstB(h,actual));
        // 正例在 B 仍可见的组件控制上下文中执行真实查询，只证明身份谓词，不冒充跨批次业务通过。
        V2PilotHarness control=new V2PilotHarness();
        control.turn(MESSAGES.get("C15").get(0),sem(TaskDirective.START_NEW,criteria("牛排","国贸",null,List.of()),List.of(),recommend()),shops("C15",false));
        TurnEvidence correct=control.turn(MESSAGES.get("C15").get(2),historicalQuery(new EntityReference.NamedEntityRef("澳洲牛排馆")),List.of());
        assertTrue(boundToFirstB(control,correct));
        // 一次真实详情读取也能证明对象身份；请求数不能冒充地址/招牌菜的完整验收。
        TurnEvidence oneRead=control.turn(MESSAGES.get("C15").get(2),sem(TaskDirective.CONTINUE,null,List.of(),List.of(
                new UserRequest.FactQueryRequest("details",new EntityReference.NamedEntityRef("澳洲牛排馆"),UserRequest.FactType.DETAIL))),List.of());
        assertTrue(boundToFirstB(control,oneRead));
    }

    @Test void feedbackObserverDistinguishesOldSecondShopFromWrongShop() throws Exception {
        for(int ordinal:List.of(2,1)) {
            V2PilotHarness h=new V2PilotHarness();
            TurnEvidence first=h.turn(MESSAGES.get("C06").get(0),sem(TaskDirective.START_NEW,criteria("面","公司附近","今晚",List.of("单人")),List.of(),recommend()),shops("C06",false));
            String old=first.workingMemory().path("activeTaskId").asText();
            var feedback=new EntityFeedback.EntityFeedbackItem(new EntityReference.OrdinalRef(ordinal),EntityFeedback.FeedbackKind.POSITIVE,EntityFeedback.FeedbackAspect.UNSPECIFIED);
            TurnEvidence actual=h.turn(MESSAGES.get("C06").get(1),sem(TaskDirective.CONTINUE,null,List.of(feedback),List.of()),List.of());
            assertEquals(ordinal==2,positiveOldFeedback(actual,old));
            // 仅测反馈归属观察器，未处理新安排，不进入六场景业务通过率。
        }
    }

    @Test void externalFailureIsObservedAndRethrownToRealExecutor() throws Exception {
        V2PilotHarness h=new V2PilotHarness();
        TurnEvidence first=h.turn(MESSAGES.get("S08").get(0),sem(TaskDirective.START_NEW,criteria("火锅","附近",null,List.of()),List.of(),recommend()),shops("S08",false));
        h.retrievalFailure=new IllegalStateException("test fact provider unavailable");
        TurnEvidence actual=h.turn(MESSAGES.get("S08").get(1),sem(TaskDirective.CONTINUE,criteria("寿司","附近",null,List.of()),List.of(),recommend()),shops("S08",true));
        assertEquals("PARTIAL",actual.fullResponse().path("decision").path("status").asText());
        assertEquals("test fact provider unavailable",actual.executionCalls().getFirst().boundaryException().path("message").asText());
        assertNull(actual.executionCalls().getFirst().observation());
        assertFalse(disjoint(h,first,actual));
    }

    @Test void missingNewArrangementCannotBeReportedAsProvenPollution() throws Exception {
        V2PilotHarness h=new V2PilotHarness();
        TurnEvidence first=h.turn(MESSAGES.get("C06").getFirst(),sem(TaskDirective.START_NEW,criteria("面","公司附近","今晚",List.of("单人")),List.of(),recommend()),shops("C06",false));
        String old=first.workingMemory().path("activeTaskId").asText();
        TurnEvidence unchanged=h.turn(MESSAGES.get("C06").get(1),sem(TaskDirective.CONTINUE,null,List.of(),List.of()),List.of());
        assertNull(arrangementIsolation(h,first,unchanged,old));
        TurnEvidence polluted=h.turn(MESSAGES.get("C06").get(1),sem(TaskDirective.CONTINUE,criteria("杭帮菜","南站","明天中午",List.of("6人聚餐")),List.of(),recommend()),shops("C06",true));
        assertEquals(Boolean.FALSE,arrangementIsolation(h,first,polluted,old));
    }

    static boolean disjoint(V2PilotHarness h,TurnEvidence first,TurnEvidence last) {
        var old=ids(first.fullResponse().path("decision").path("recommendations"),"shopId");
        var now=ids(last.fullResponse().path("decision").path("recommendations"),"shopId");
        return h.assertions.assertRecommendationDisjoint(old,now);
    }
    static boolean identityClarification(TurnEvidence turn) {
        return "CLARIFYING".equals(turn.fullResponse().path("decision").path("status").asText())
                &&turn.fullResponse().path("answer").asText().startsWith("请明确是哪一家或哪一批推荐")&&turn.executionCalls().isEmpty();
    }
    static boolean positiveOldFeedback(TurnEvidence turn,String old) {
        JsonNode task=task(turn,old);if(task==null||!task.path("v2SelectedShopId").isNull())return false;
        for(JsonNode feedback:task.path("v2FeedbackLedger"))if(feedback.path("shopId").asInt()==22&&feedback.path("kind").asText().equals("POSITIVE"))return true;
        return false;
    }
    private static boolean isolated(V2PilotHarness h,TurnEvidence before,TurnEvidence after,String old) {
        String current=after.workingMemory().path("activeTaskId").asText();
        return !old.equals(current)&&h.matches(Map.of("criteria",Map.of("equals",h.taskProjection(before,old).get("criteria"))),h.taskProjection(after,old))
                &&h.matches(Map.of("criteria",Map.of("equals",h.json.convertValue(criteria("杭帮菜","南站","明天中午",List.of("6人聚餐","上档次")),Map.class))),h.taskProjection(after,current));
    }
    static Boolean arrangementIsolation(V2PilotHarness h,TurnEvidence before,TurnEvidence after,String old) {
        if (!h.matches(Map.of("criteria",Map.of("equals",h.taskProjection(before,old).get("criteria"))),h.taskProjection(after,old))) return false;
        String current=after.workingMemory().path("activeTaskId").asText();
        if (old.equals(current)||task(after,current)==null) return null;
        // 旧安排不变与新安排独立分别取证；无需强制合法OR分支或偏好字段序列化完全一致。
        JsonNode criteria=task(after,current).path("v2Criteria");
        Set<String> alternatives=new HashSet<>();criteria.path("cuisine").path("anyOf").forEach(c->alternatives.add(c.asText()));
        return (List.of("杭帮菜","粤菜").contains(criteria.path("cuisine").path("include").asText()) || alternatives.equals(Set.of("杭帮菜","粤菜")))
                && criteria.path("location").path("poi").asText().contains("南站")
                && !criteria.path("preferences").toString().contains("单人");
    }
    static boolean boundToFirstB(V2PilotHarness h,TurnEvidence turn) {
        List<Map<String,Object>> bound=turn.executionCalls().stream().filter(call->call.action().has("shop"))
                .map(call->Map.<String,Object>of("shopId",call.action().path("shop").path("shopId").longValue())).toList();
        return !bound.isEmpty()&&h.matches(Map.of("groundedEntities",bound.stream().map(ignored->Map.of("shopId",22L)).toList()),Map.of("groundedEntities",bound));
    }
    private static TurnSemantics historicalQuery(EntityReference ref) {
        return sem(TaskDirective.CONTINUE,null,List.of(),List.of(new UserRequest.FactQueryRequest("address",ref,UserRequest.FactType.DETAIL),new UserRequest.FactQueryRequest("signature",ref,UserRequest.FactType.DETAIL)));
    }
    private static List<UserRequest> recommend(){return List.of(new UserRequest.RecommendationRequest("recommend"));}
    private static TurnSemantics sem(TaskDirective directive,DiningCriteria criteria,List<EntityFeedback> feedback,List<UserRequest> requests) {
        List<RequirementChange> changes=criteria==null?List.of():List.of(new RequirementChange.CriteriaPatch(new DiningCriteriaPatch(criteria.location(),criteria.cuisine(),criteria.budget(),criteria.distance(),criteria.diningTime(),criteria.preferences()),Set.of()));
        return new TurnSemantics(directive,changes,feedback,requests,List.of());
    }
    private static DiningCriteria criteria(String cuisine,String poi,String time,List<String> preferences) {
        return new DiningCriteria(new DiningCriteria.LocationCriteria("福州市",null,poi),new DiningCriteria.CuisineCriteria(cuisine,List.of()),null,null,
                time==null?null:new DiningCriteria.DiningTimeCriteria(time),new DiningCriteria.SemanticPreferences(preferences.stream().map(p->new DiningCriteria.SemanticPreference(p,DiningCriteria.Importance.MUST)).toList(),List.of()));
    }
    static List<DecisionRecommendation> shops(String scenario,boolean next) {
        List<Long> ids=next?(scenario.equals("C15")?List.of(44L,55L):List.of(44L,55L,66L)):List.of(11L,22L,33L);
        List<String> names=switch(scenario){case "C15"->next?List.of("社区西餐吧","精酿牛排小馆"):List.of("连锁西餐厅","澳洲牛排馆","意式简餐");case "S13"->List.of("包子铺","便利店","煎饼摊");case "C06"->next?List.of("测试杭帮菜A","测试杭帮菜B","测试粤菜C"):List.of("测试面馆A","重庆小面","测试面馆C");case "S09"->next?List.of("测试四楼店D","测试四楼店E","测试四楼店F"):List.of("测试湘菜","测试披萨","测试泰餐");default->List.of("测试门店A","测试门店B","测试门店C");};
        List<DecisionRecommendation> shops=new ArrayList<>();
        for(int n=0;n<ids.size();n++){DecisionRecommendation shop=new DecisionRecommendation();shop.setShopId(ids.get(n));shop.setShopName(names.get(n));shop.setAvgPrice(scenario.equals("S04")&&!next?List.of(120L,150L,180L).get(n):40L);shop.setDistanceKm(0.3);shop.setAddress("仅测试资料，非真实商户");
            shop.setCuisine(switch(scenario){case "S04"->"日料";case "S08"->next?"寿司":"火锅";case "S09"->next?"测试其他餐饮":List.of("湘菜","披萨","泰餐").get(n);case "S13"->"早餐";case "C06"->next?(n==2?"粤菜":"杭帮菜"):"面";default->"牛排";});shops.add(shop);}return shops;
    }
    static Plan plan(String id) throws Exception {
        int m=switch(id){case "S13"->2;case "C06","C15"->5;default->3;};
        int f=switch(id){case "S04","C06","C15"->3;default->2;};
        List<ItemPlan> items=new ArrayList<>();for(int n=1;n<=m;n++)items.add(new ItemPlan(id+"-M"+n,true,true,true));for(int n=1;n<=f;n++)items.add(new ItemPlan(id+"-F"+n,true,true,true));
        String sha=new String(new ProcessBuilder("git","rev-parse","HEAD").start().getInputStream().readAllBytes()).trim();
        boolean dirty=!new String(new ProcessBuilder("git","status","--porcelain").start().getInputStream().readAllBytes()).isBlank();
        return new Plan(id,"v1.1",FREEZE,sha,dirty,Mode.CONTROLLED_SEMANTICS,"TEST_ONLY known candidate identities/prices; menu and subjective facts not adjudicated",items,List.of("SemanticInterpreter","ShopRetrievalEngine","LocationResolutionProvider","ShopMapper","WorkingMemoryVersionService","ChatMemoryService","ConversationEventService"),
                Map.of("referenceDateTime","2026-10-13T18:00:00+08:00","city","福州市（测试上下文，不是真实商户事实）","budgetUnit","C15测试上下文事前指定人均；不推广为原话唯一解释","focusAndClicks","no user click or previous selection; actual request/memory recorded","factSource","TEST_ONLY candidate/detail/location providers; no real database"));
    }
    static Map<String,List<String>> messages() {
        try {
            String spec=Files.readString(Path.of("doc/eval/V2自然语言多轮评测-业务验收规范-v1.md"));
            String frozen=new String(new ProcessBuilder("git","show",FREEZE+":doc/eval/V2自然语言多轮评测-业务验收规范-v1.md").start().getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            if(!spec.replace("\r\n","\n").equals(frozen.replace("\r\n","\n")))throw new IllegalStateException("frozen business specification changed");
            Map<String,List<String>> result=new HashMap<>();Matcher scene=Pattern.compile("(?ms)^### ([SC]\\d{2})｜(.*?)(?=^### [SC]\\d{2}｜|^## 第三)").matcher(spec);
            while(scene.find()){Matcher message=Pattern.compile("(?m)^- 第 \\d+ 轮用户：“([^”]+)”").matcher(scene.group(2));List<String> texts=new ArrayList<>();while(message.find())texts.add(message.group(1));result.put(scene.group(1),List.copyOf(texts));}return result;
        } catch(Exception e){throw new IllegalStateException("frozen original utterances unavailable",e);}
    }
}
