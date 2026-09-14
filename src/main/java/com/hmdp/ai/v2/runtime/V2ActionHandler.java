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
        AgentToolResult result = toolRegistry.find(name).execute(Map.of("shopId", action.shop().shopId()));
        return new ExecutionObservation(action.requestId(), ExecutionObservation.Status.SUCCESS, List.of(), null, List.of(),
                result.getDisplayText(), result.getSummary(), typedValue(action.fact(), result.getFacts()));
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
