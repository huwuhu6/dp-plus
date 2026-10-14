package com.hmdp.ai.evaluation;

import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.tool.*;
import com.hmdp.ai.v2.evidence.MainMealSpiceEvidence;
import com.hmdp.ai.v2.semantic.*;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** TEST_ONLY：首轮经真实 POST 建立展示，不要求模型碰巧生成指定名单；后续语义不替换。 */
final class ControlledFourFixtures {
    static final List<String> IDS=List.of("S02","S06","C03","C23");
    static String branch(String id){return switch(id){
        case "S02" -> "TEST_ONLY PRICE_KNOWN_PER_PERSON_40; SPICE_UNKNOWN";
        case "S06" -> "TEST_ONLY MENU_UNKNOWN";
        case "C03" -> "TEST_ONLY INGREDIENT_AND_SPICE_FACTS_UNKNOWN";
        case "C23" -> "TEST_ONLY SAME_SHOP_NON_SPICY_MAIN_MEAL_CONFIRMED";
        default -> throw new IllegalArgumentException(id);
    };}
    static List<DecisionRecommendation> shops(String id,boolean after){
        List<String> foods=switch(id){
            case "S02" -> List.of("重庆火锅","椰子鸡锅","砂锅粥");
            case "S06" -> List.of("连锁汉堡","韩国炸鸡","美式炸鸡汉堡");
            case "C03" -> List.of("川菜","川菜","川菜");
            case "C23" -> List.of("酸菜鱼","米线","炸鸡汉堡");
            default -> throw new IllegalArgumentException(id);
        };
        List<Long> ids=after?List.of(44L,55L,66L):List.of(11L,22L,33L);
        List<DecisionRecommendation> result=new ArrayList<>();
        for(int n=0;n<3;n++){
            var s=new DecisionRecommendation();s.setShopId(ids.get(n));
            s.setShopName("TEST_ONLY"+foods.get(n)+(id.equals("C03")?(after?"候选":"重辣")+ids.get(n):""));
            s.setCuisine(foods.get(n));s.setAvgPrice(40L);s.setDistanceKm(0.3);
            s.setAddress(id.equals("C23")?"TEST_ONLY 福州市测试万达负一楼":"TEST_ONLY 福州市测试区域，非现实商户");result.add(s);
        }
        return result;
    }
    static TurnSemantics first(String id){
        var preferences=id.equals("C03")?List.of(new DiningCriteria.SemanticPreference("够麻够辣",DiningCriteria.Importance.PREFER)):
                id.equals("S02")?List.of(new DiningCriteria.SemanticPreference("热乎",DiningCriteria.Importance.PREFER)):List.<DiningCriteria.SemanticPreference>of();
        String poi=switch(id){case "C03"->"万象城";case "C23"->"万达负一楼";default->"TEST_ONLY已确认附近位置";};
        String time=id.equals("C03")?"今晚下班":id.equals("S02")?"晚上":null;
        var patch=new DiningCriteriaPatch(new DiningCriteria.LocationCriteria("福州市",null,poi),
                id.equals("C03")?new DiningCriteria.CuisineCriteria("川菜",List.of()):null,null,null,
                time==null?null:new DiningCriteria.DiningTimeCriteria(time),
                new DiningCriteria.SemanticPreferences(preferences,List.of()),id.equals("S02")?2:null);
        return new TurnSemantics(TaskDirective.START_NEW,List.of(new RequirementChange.CriteriaPatch(patch,Set.of())),
                List.of(),List.of(new UserRequest.RecommendationRequest("controlled-first-display")),List.of());
    }
    static List<Map<String,Object>> configure(V2PilotHarness h,String id,boolean priceKnown,Object mealEvidence){
        List<Map<String,Object>> reads=new ArrayList<>();
        var runtime=ReflectionTestUtils.getField(h.chat,"v2ChatOrchestrator");
        var actions=ReflectionTestUtils.getField(runtime,"actions");
        var registry=(AgentToolRegistry)ReflectionTestUtils.getField(actions,"toolRegistry");
        var tool=registry.find("get_shop_detail");
        var mapper=(ShopMapper)ReflectionTestUtils.getField(tool,"shopMapper");
        var catalog=new ArrayList<>(shops(id,false));catalog.addAll(shops(id,true));
        doAnswer(i->{
            long requested=((Number)i.getArgument(0)).longValue();
            var c=catalog.stream().filter(s->s.getShopId()==requested).findFirst().orElseThrow();
            var s=new Shop();s.setId(requested);s.setName(c.getShopName());s.setAvgPrice(priceKnown?40L:null);
            s.setAddress(c.getAddress());s.setOpenHours("TEST_ONLY 10:00-22:00");return s;
        }).when(mapper).selectById(any());
        // 保留原工具读及结果观察器。只在隔离事实提供者返回中增加有来源的类型化菜单证据。
        // 不覆盖原 execute spy：包装 registry 返回值，真实基础事实工具仍执行并被 Harness 捕获。
        BaseAgentTool provider=new BaseAgentTool(){
            public String name(){return tool.name();}
            public String description(){return tool.description();}
            public Map<String,Object> parameterSchema(){return tool.parameterSchema();}
            public AgentToolResult execute(Map<String,Object> input){
                AgentToolResult r=tool.execute(input);
                r.getFacts().put("fixtureSource","TEST_ONLY controlled catalog; no real business database");
                r.getFacts().put("menuEvidenceStatus",mealEvidence==null?"UNKNOWN":"CONTROLLED_TYPED_EVIDENCE");
                if(mealEvidence!=null)r.getFacts().put("mainMealSpiceEvidence",mealEvidence);
                reads.add(Map.of("tool",name(),"input",Map.copyOf(input),"result",h.json.valueToTree(r)));
                return r;
            }
        };
        when(registry.find("get_shop_detail")).thenReturn(provider);
        BaseAgentTool unknown=new BaseAgentTool(){
            public String name(){return "search_shop_evidence";}
            public String description(){return "TEST_ONLY no external menu evidence";}
            public Map<String,Object> parameterSchema(){return Map.of("type","object");}
            public AgentToolResult execute(Map<String,Object> input){
                var r=new AgentToolResult().summary("TEST_ONLY: menu evidence unavailable")
                        .displayText("TEST_ONLY商户资料暂无可核验菜单，无法确认具体商品及辣度；缺少资料不代表没有商品。");
                r.getFacts().put("shopId",input.get("shopId"));r.getFacts().put("source","TEST_ONLY MENU_UNKNOWN");
                reads.add(Map.of("tool",name(),"input",Map.copyOf(input),"result",h.json.valueToTree(r)));return r;
            }
        };
        when(registry.find("search_shop_evidence")).thenReturn(unknown);
        return reads;
    }
    static Object mainMeal(){return new MainMealSpiceEvidence(22,"TEST_ONLY 原味米线（不放辣椒）",true,
            MainMealSpiceEvidence.Preparation.NON_SPICY,"TEST_ONLY frozen menu fixture v1");}
}
