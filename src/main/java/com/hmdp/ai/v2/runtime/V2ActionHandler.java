package com.hmdp.ai.v2.runtime;

import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.dto.DecisionRequest;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.service.ConsumptionDecisionService;
import com.hmdp.ai.tool.AgentToolRegistry;
import com.hmdp.ai.tool.AgentToolResult;
import com.hmdp.ai.v2.plan.ExecutionAction;
import com.hmdp.ai.v2.runtime.StaticPlanExecutor.ActionExecutor;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

/** Real read-only action bindings. It receives only frozen action arguments, never WorkingMemory. */
@Service
public class V2ActionHandler implements ActionExecutor {
    @Resource private ConsumptionDecisionService consumptionDecisionService;
    @Resource private AgentToolRegistry toolRegistry;

    @Override public ExecutionObservation execute(ExecutionAction action) {
        return switch (action) {
            case ExecutionAction.SearchAction search -> search(search);
            case ExecutionAction.ToolAction tool -> tool(tool);
            case ExecutionAction.CompareAction compare -> new ExecutionObservation(compare.requestId(), ExecutionObservation.Status.FAILURE,
                    List.of(), null, List.of(), null, "V2 compare is not available yet");
            case ExecutionAction.GeneralAnswerAction general -> new ExecutionObservation(general.requestId(), ExecutionObservation.Status.SUCCESS,
                    List.of(), null, List.of(), general.topic(), null);
            case ExecutionAction.EmitDomainEffectAction effect -> new ExecutionObservation(effect.requestId(), ExecutionObservation.Status.SUCCESS,
                    List.of(), null, List.of(effect.effect()), null, null);
        };
    }
    private ExecutionObservation search(ExecutionAction.SearchAction action) {
        DecisionRequest request = new DecisionRequest();
        request.setQuery(query(action));
        request.setMaxCandidates(action.spec().count() > 0 ? action.spec().count() : 3);
        request.setExcludeShopIds(action.spec().excludedShopIds().stream().toList());
        if (action.spec().searchAnchor() != null) {
            var anchor = action.spec().searchAnchor();
            request.setLatitude(anchor.latitude()); request.setLongitude(anchor.longitude());
            request.setProvince(anchor.province()); request.setCity(anchor.city()); request.setDistrict(anchor.district());
            request.setLocationName(anchor.canonicalName()); request.setLocationStatus("AVAILABLE"); request.setUseLocationScope(true);
        }
        DecisionResponse response = consumptionDecisionService.decide(request, V2CriteriaProjection.retrieval(action.spec().criteria()));
        List<Long> ids = response.getRecommendations() == null ? List.of() : response.getRecommendations().stream()
                .map(DecisionRecommendation::getShopId).filter(java.util.Objects::nonNull).toList();
        return new ExecutionObservation(action.requestId(), ids.isEmpty() ? ExecutionObservation.Status.EMPTY : ExecutionObservation.Status.SUCCESS,
                ids, null, List.of(), null, response.getAnswer());
    }
    private ExecutionObservation tool(ExecutionAction.ToolAction action) {
        String name = switch (action.fact()) {
            case VOUCHER -> "query_shop_vouchers";
            case EVIDENCE, REVIEW, QUEUE -> "search_shop_evidence";
            default -> "get_shop_detail";
        };
        AgentToolResult result = toolRegistry.find(name).execute(Map.of("shopId", action.shop().shopId()));
        return new ExecutionObservation(action.requestId(), ExecutionObservation.Status.SUCCESS, List.of(), null, List.of(),
                result.getDisplayText(), result.getSummary());
    }
    private String query(ExecutionAction.SearchAction action) {
        var criteria = action.spec().criteria();
        if (criteria.cuisine() != null && criteria.cuisine().include() != null && !criteria.cuisine().include().isBlank()) return criteria.cuisine().include();
        return "餐厅推荐";
    }
}
