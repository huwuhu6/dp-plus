package com.hmdp.ai.v2.runtime;

import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.tool.AgentToolRegistry;
import com.hmdp.ai.tool.AgentToolResult;
import com.hmdp.ai.v2.plan.ExecutionAction;
import com.hmdp.ai.v2.runtime.StaticPlanExecutor.ActionExecutor;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

/** Real read-only action bindings. It receives only frozen action arguments, never WorkingMemory. */
@Service
public class V2ActionHandler implements ActionExecutor {
    @Resource private ShopRetrievalEngine shopRetrievalEngine;
    @Resource private AgentToolRegistry toolRegistry;

    @Override public ExecutionObservation execute(ExecutionAction action) {
        return switch (action) {
            case ExecutionAction.SearchAction search -> search(search);
            case ExecutionAction.ToolAction tool -> tool(tool);
            case ExecutionAction.CompareAction compare -> new ExecutionObservation(compare.requestId(), ExecutionObservation.Status.UNSUPPORTED,
                    List.of(), null, List.of(), null, "目前暂不支持商户对比。");
            case ExecutionAction.GeneralAnswerAction general -> new ExecutionObservation(general.requestId(), ExecutionObservation.Status.UNSUPPORTED,
                    List.of(), null, List.of(), null, "目前暂不支持开放式知识问答。");
            case ExecutionAction.EmitDomainEffectAction effect -> new ExecutionObservation(effect.requestId(), ExecutionObservation.Status.SUCCESS,
                    List.of(), null, List.of(effect.effect()), null, null);
        };
    }
    private ExecutionObservation search(ExecutionAction.SearchAction action) {
        ShopRetrievalEngine.RetrievalResult result = shopRetrievalEngine.retrieve(action.spec());
        if (!result.supported()) return new ExecutionObservation(action.requestId(), ExecutionObservation.Status.UNSUPPORTED,
                List.of(), null, List.of(), null, result.detail());
        List<Long> ids = result.candidates().stream().map(DecisionRecommendation::getShopId).filter(java.util.Objects::nonNull).toList();
        return new ExecutionObservation(action.requestId(), ids.isEmpty() ? ExecutionObservation.Status.EMPTY : ExecutionObservation.Status.SUCCESS,
                ids, null, List.of(), null, null, null, result.candidates());
    }
    private ExecutionObservation tool(ExecutionAction.ToolAction action) {
        String name = switch (action.fact()) {
            case VOUCHER -> "query_shop_vouchers";
            case EVIDENCE, REVIEW, QUEUE -> "search_shop_evidence";
            default -> "get_shop_detail";
        };
        Map<String,Object> input=new java.util.LinkedHashMap<>();input.put("shopId",action.shop().shopId());
        if(action.question()!=null)input.put("attribute",action.question());
        AgentToolResult result = toolRegistry.find(name).execute(input);
        if(action.fact()==com.hmdp.ai.v2.semantic.UserRequest.FactType.PRICE || action.fact()==com.hmdp.ai.v2.semantic.UserRequest.FactType.PRODUCT)
            return merchantFact(action,result);
        if(action.fact()==com.hmdp.ai.v2.semantic.UserRequest.FactType.NON_SPICY_MAIN_MEAL) {
            Object raw=result.getFacts()==null?null:result.getFacts().get("mainMealSpiceEvidence");
            var evidence=raw instanceof com.hmdp.ai.v2.evidence.MainMealSpiceEvidence e && e.admissibleFor(action.shop().shopId())?e:null;
            ObservationValue value=evidence==null?null:new ObservationValue.BooleanValue(evidence.preparation()==com.hmdp.ai.v2.evidence.MainMealSpiceEvidence.Preparation.NON_SPICY);
            String answer=evidence==null?"无法确认这家店能否提供符合本次用餐的不辣主餐；条件尚未证实，暂不选店。":
                    evidence.preparation()==com.hmdp.ai.v2.evidence.MainMealSpiceEvidence.Preparation.NON_SPICY?"有主餐不辣证据："+evidence.mealName()+"（来源："+evidence.source()+"）。":
                    "仅能确认主餐可减辣，不能确认最终不辣；条件尚未满足，暂不选店。";
            return new ExecutionObservation(action.requestId(),ExecutionObservation.Status.SUCCESS,List.of(),null,List.of(),answer,null,value);
        }
        return new ExecutionObservation(action.requestId(), ExecutionObservation.Status.SUCCESS, List.of(), null, List.of(),
                result.getDisplayText(), result.getSummary(), typedValue(action.fact(), result.getFacts()));
    }
    private ExecutionObservation merchantFact(ExecutionAction.ToolAction action,AgentToolResult result){
        Map<String,Object> facts=result.getFacts()==null?Map.of():result.getFacts();long id=action.shop().shopId();
        Object rawShop=facts.get("shop");var shop=rawShop instanceof com.hmdp.entity.Shop s && s.getId()!=null && s.getId()==id?s:null;
        String merchant=shop==null?"该店":shop.getName();String answer,source=null;ObservationValue value=null;
        if(action.fact()==com.hmdp.ai.v2.semantic.UserRequest.FactType.PRICE){
            if(shop!=null && shop.getAvgPrice()!=null && shop.getAvgPrice()>0 && facts.get("shopSource") instanceof String s && !s.isBlank()){
                source=String.valueOf(facts.getOrDefault("fixtureSource",facts.get("shopSource")));
                value=new ObservationValue.NumericValue(BigDecimal.valueOf(shop.getAvgPrice()));
                answer=merchant+"：人均 "+shop.getAvgPrice()+" 元（来源："+source+"）；这是人均参考价，不是两人总价或具体套餐报价。";
            }else answer="无法确认"+merchant+"的具体商户价格，现有资料没有可靠人均或套餐价格。";
        }else{
            Object raw=facts.get("productAttributeEvidence");
            var evidence=raw instanceof com.hmdp.ai.v2.evidence.ProductAttributeEvidence e && e.supports(id,action.question())?e:null;
            if(evidence!=null){source=evidence.source();value=new ObservationValue.BooleanValue(evidence.available());
                answer=merchant+(evidence.available()?"有":"已确认没有")+"符合“"+action.question()+"”的商品（来源："+source+"）。";
            }else answer="无法确认"+merchant+"是否提供符合“"+action.question()+"”的商品；暂无可核验的菜单资料，未查到记录不等于没有。";
        }
        return new ExecutionObservation(action.requestId(),ExecutionObservation.Status.SUCCESS,List.of(),null,List.of(),answer,null,value,List.of(),
                new MerchantFactObservation(id,action.fact(),action.question(),value==null?MerchantFactObservation.State.UNKNOWN:MerchantFactObservation.State.KNOWN,source));
    }
    private ObservationValue typedValue(com.hmdp.ai.v2.semantic.UserRequest.FactType fact, Map<String, Object> facts) {
        if (facts == null) return null;
        Object value = switch (fact) {
            case PRIVATE_ROOM -> facts.get("privateRoom");
            case QUEUE -> facts.get("queueMinutes");
            default -> null;
        };
        if (value instanceof Boolean bool) return new ObservationValue.BooleanValue(bool);
        if (value instanceof Number number) return new ObservationValue.NumericValue(new BigDecimal(number.toString()));
        return null; // Tools without a stable fact field are insufficient for guarded execution.
    }
}
