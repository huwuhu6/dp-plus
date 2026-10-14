package com.hmdp.ai.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.hmdp.ai.evaluation.V2PilotEvaluation.*;
import java.util.*;

/** 冻结场景首轮前提的只读门控，消费实际 API 展示与 memory；不生成或重命名候选。 */
final class ExpandedScenarioReadiness {
    static Gate assess(String id,JsonNode first) {
        JsonNode visible=first.path("evidence").path("fullResponse").path("decision").path("recommendations");
        JsonNode memory=first.path("after");JsonNode task=null;
        for(JsonNode t:memory.path("tasks"))if(t.path("taskId").asText().equals(memory.path("activeTaskId").asText()))task=t;
        JsonNode batches=task==null?memory.path("absent"):task.path("recommendationBatches");
        JsonNode stored=batches.isEmpty()?memory.path("absent"):batches.get(batches.size()-1).path("candidates");
        List<Long> shown=ids(visible),saved=ids(stored);
        var paths=List.of(id+"-turns.json/1/evidence/fullResponse",id+"-turns.json/1/after");
        if(!shown.equals(saved))return new Gate(Readiness.EVIDENCE_CONFLICT,Stage.FIRST_TURN_COVERAGE,paths,"B: display/memory IDs or order differ: "+shown+" / "+saved);
        if(new HashSet<>(shown).size()!=shown.size())return new Gate(Readiness.NOT_READY,Stage.FIRST_TURN_COVERAGE,paths,"A: duplicate merchant identities in display");
        for(int n=0;n<visible.size();n++)if(!visible.get(n).path("shopName").equals(stored.get(n).path("shopName")))
            return new Gate(Readiness.EVIDENCE_CONFLICT,Stage.FIRST_TURN_COVERAGE,paths,"B: display/memory names differ");
        List<String> gaps=new ArrayList<>();
        int count=Set.of("S03","S10").contains(id)?2:3;
        if(shown.size()!=count)gaps.add("A: expected "+count+" displayed merchants, actual "+shown.size());
        if(!shown.isEmpty()) {
            boolean categories=switch(id) {
                case "S01"->all(visible,"米线|面");case "S02"->at(visible,0,"火锅")&&at(visible,1,"椰子鸡")&&at(visible,2,"砂锅粥");
                case "S03"->all(visible,"酸菜鱼");case "S05"->all(visible,"简餐|粉面|面");
                case "S06"->at(visible,0,"汉堡")&&at(visible,1,"韩式炸鸡")&&at(visible,2,"美式炸鸡汉堡");
                case "S10"->all(visible,"茶餐厅");case "C03"->all(visible,"川菜");
                case "C04"->at(visible,0,"老街")&&all(visible,"顺德");
                case "C10"->at(visible,0,"粤菜酒楼")&&at(visible,1,"顺德")&&at(visible,2,"茶餐厅");
                case "C23"->at(visible,0,"酸菜鱼")&&at(visible,1,"米线")&&at(visible,2,"炸鸡汉堡");
                default->throw new IllegalArgumentException("unplanned scenario");
            };
            if(!categories)gaps.add("A/E: frozen ordered merchant categories or supporting facts not established");
        }
        if(Set.of("S01","S03","S05","S06","S10","C10").contains(id)) {
            JsonNode anchor=task==null?memory.path("absent"):task.path("v2SearchAnchor");
            if(!anchor.hasNonNull("latitude") || !anchor.hasNonNull("longitude"))gaps.add("C/E: no reliable nearby/location anchor provided or established");
        }
        if(id.equals("S01") && (!atDistance(visible,0,1.2) || !allDistances(visible)))gaps.add("E: required first 1.2km and comparable candidate distances missing");
        if(Set.of("C03","C23").contains(id) && (task==null || !task.path("v2SearchAnchor").hasNonNull("poiId")))gaps.add("E: named mall/location not resolved");
        // 当前投影没有结构化来源支持这些事实；不能用店型、自由标签或名称冒充证据。
        String missingFact=switch(id) {case "S03"->"multi-person portion";case "S05"->"actual afternoon opening";case "C03"->"spicy menu";
            case "C04"->"first merchant parking difficulty from turn-2 fact";case "C10"->"A alias and signature roast-goose menu";case "C23"->"basement floor";default->null;};
        if(missingFact!=null)gaps.add("E: current observation has no structured source for "+missingFact+"; manual evidence required");
        return new Gate(gaps.isEmpty()?Readiness.READY:Readiness.NOT_READY,Stage.FIRST_TURN_COVERAGE,paths,
                gaps.isEmpty()?"actual ordered display and stored batch meet frozen prerequisites":String.join("; ",gaps));
    }
    private static List<Long> ids(JsonNode nodes){List<Long> ids=new ArrayList<>();for(JsonNode n:nodes)if(n.hasNonNull("shopId"))ids.add(n.path("shopId").asLong());return ids;}
    private static String text(JsonNode n){return n.path("shopName").asText()+" "+n.path("cuisine").asText()+" "+n.path("referenceTags");}
    private static boolean at(JsonNode nodes,int n,String pattern){return nodes.size()>n && text(nodes.get(n)).matches("(?s).*(?:"+pattern+").*");}
    private static boolean all(JsonNode nodes,String pattern){if(nodes.isEmpty())return false;for(int n=0;n<nodes.size();n++)if(!at(nodes,n,pattern))return false;return true;}
    private static boolean atDistance(JsonNode n,int i,double d){return n.size()>i && n.get(i).path("distanceKm").isNumber()&&Math.abs(n.get(i).path("distanceKm").asDouble()-d)<0.0001;}
    private static boolean allDistances(JsonNode n){if(n.isEmpty())return false;for(JsonNode s:n)if(!s.path("distanceKm").isNumber())return false;return true;}
}
