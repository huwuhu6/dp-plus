package com.hmdp.ai.service;

import com.hmdp.ai.dto.ContextRewriteResult;
import com.hmdp.ai.dto.DecisionContextQuery;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.TurnCommand;
import com.hmdp.ai.dto.TurnCommandSet;
import com.hmdp.ai.dto.ResolvedShopReference;
import com.hmdp.ai.util.CuisineCanonicalizer;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * First, deliberately small, canonical turn-semantics boundary. It only classifies
 * control semantics; deterministic extractors and reducers remain the fact authorities.
 */
@Service
public class TurnUnderstandingService {
    public TurnCommandSet understand(String originalMessage, String effectiveMessage,
                                      List<Map<String, Object>> history, ContextRewriteResult rewrite) {
        return understand(originalMessage, effectiveMessage, history, rewrite, null, null);
    }

    /**
     * Adds paused-decision context without changing the ordinary turn semantics
     * contract. Recovery commands are emitted only for WAITING_RELAXATION and only
     * when the paused constraints still contain a concrete food target.
     */
    public TurnCommandSet understand(String originalMessage, String effectiveMessage,
                                      List<Map<String, Object>> history, ContextRewriteResult rewrite,
                                      String decisionStatus, DecisionConstraints pausedConstraints) {
        String text = normalize(originalMessage);
        TurnCommandSet result = new TurnCommandSet();
        boolean hasReference = rewrite != null && rewrite.getResolvedReferences() != null
                && !rewrite.getResolvedReferences().isEmpty();
        boolean contextQuery = isContextQuery(text, history);
        if (contextQuery) {
            DecisionContextQuery query = new DecisionContextQuery();
            query.setType(contextQueryType(text, history));
            query.setConstraintKey(constraintKey(text));
            if (query.getConstraintKey() != null && query.getConstraintKey().startsWith("preference:")) {
                query.setPreferenceValue(query.getConstraintKey().substring("preference:".length()));
            }
            result.setContextQuery(query);
            result.setContextQueryRequested(true);
            result.getCommands().add(new TurnCommand(TurnCommand.Type.ASK_DECISION_CONTEXT,
                    query.getType().name(), query.getConstraintKey()));
        }
        boolean mutation = hasMutationSignal(text, rewrite, hasReference);
        result.setMutationRequested(mutation);
        result.setReferenceOnly(hasReference && !mutation);
        if (hasReference) result.getCommands().add(new TurnCommand(TurnCommand.Type.REFERENCE, null, null));
        if (result.isReferenceOnly()) result.getCommands().add(new TurnCommand(TurnCommand.Type.ASK_SHOP_FACT, null, null));
        if (containsAny(text, "我附近", "我这附近", "当前位置", "当前定位", "我身边")) {
            result.getCommands().add(new TurnCommand(TurnCommand.Type.SET_LOCATION_INTENT, "locationIntent", "CURRENT_DEVICE"));
        }
        if (text.contains("除了")) {
            result.getCommands().add(new TurnCommand(TurnCommand.Type.EXCLUDE_CONSTRAINT, "cuisine", null));
        }
        if (mutation) result.getCommands().add(new TurnCommand(TurnCommand.Type.SET_CONSTRAINT, null, null));
        if (isBroadeningFoodRecovery(text, decisionStatus, pausedConstraints)) {
            result.getCommands().add(new TurnCommand(TurnCommand.Type.BROADEN_FOOD_SCOPE,
                    "foodTarget", "CLEAR_KEYWORD_AND_CUISINE"));
        }
        return result;
    }

    private boolean isBroadeningFoodRecovery(String text, String decisionStatus,
                                             DecisionConstraints pausedConstraints) {
        if (!"WAITING_RELAXATION".equals(decisionStatus) || pausedConstraints == null) return false;
        boolean hasFoodTarget = hasText(pausedConstraints.getKeyword()) || hasText(pausedConstraints.getCuisine());
        if (!hasFoodTarget) return false;
        boolean nearbyScope = Boolean.TRUE.equals(pausedConstraints.getNearby())
                || containsAny(text, "附近", "周边", "周围");
        if (!nearbyScope) return false;
        boolean repeatsSpecificTarget = containsAny(text, pausedConstraints.getKeyword(), pausedConstraints.getCuisine());
        boolean explicitAbandonment = containsAny(text, "随便", "都行", "都可以", "不限", "不一定", "不必");
        if (repeatsSpecificTarget && !explicitAbandonment) return false;
        boolean relinquishesSpecificTarget = containsAny(text, "随便", "都行", "都可以", "不限",
                "不一定", "其他", "啥", "什么");
        boolean foodSearchIntent = containsAny(text, "吃", "餐", "店", "看看", "找", "推荐", "附近");
        return relinquishesSpecificTarget && foodSearchIntent;
    }

    /** A resolved reference turn does not become a criteria mutation merely because a shop name contains a cuisine. */
    public boolean shouldApplyReferenceMutation(TurnCommandSet semantics) {
        return semantics != null && semantics.isMutationRequested() && !semantics.isReferenceOnly();
    }

    private boolean isContextQuery(String text, List<Map<String, Object>> history) {
        if (text.isEmpty()) return false;
        boolean scopeWords = containsAny(text, "条件", "要求", "过滤", "筛选", "依据");
        boolean allScope = containsAny(text, "什么", "哪些", "所有", "全部", "现在", "当前");
        if (scopeWords && allScope) return true;
        if (containsAny(text, "说过", "之前", "前面", "来源")
                && (scopeWords || text.contains("偏好") || constraintKey(text) != null)) return true;
        if (text.equals("所有") || text.equals("全部")) {
            Map<String, Object> latestAssistant = latestAssistantMessage(history);
            if (latestAssistant != null) {
                String content = normalize(String.valueOf(latestAssistant.get("content")));
                if (containsAny(content, "哪一项", "预算、距离", "告诉我你想核对")) return true;
            }
        }
        return (text.contains("推荐") && (text.contains("为什么") || text.contains("依据") || text.contains("理由")))
                || (text.contains("为什么") && text.contains("排"))
                || text.contains("根据什么推荐") || text.contains("什么理由推荐");
    }

    private DecisionContextQuery.QueryType contextQueryType(String text, List<Map<String, Object>> history) {
        if ((containsAny(text, "条件", "要求", "过滤", "筛选") && containsAny(text, "什么", "哪些", "所有", "全部", "现在", "当前"))
                || text.equals("所有") || text.equals("全部")) return DecisionContextQuery.QueryType.CURRENT_CRITERIA;
        if (containsAny(text, "查哪里", "搜索范围", "搜的是哪里")) return DecisionContextQuery.QueryType.EXECUTED_SEARCH_SCOPE;
        if (text.contains("推荐") && (text.contains("为什么") || text.contains("依据") || text.contains("理由"))) return DecisionContextQuery.QueryType.WHY_RECOMMENDED;
        return DecisionContextQuery.QueryType.CONSTRAINT_PROVENANCE;
    }

    private String constraintKey(String text) {
        if (text.contains("预算")) return "budgetPerPerson";
        if (text.contains("附近")) return "nearby";
        if (containsAny(text, "半径", "距离", "多远", "更近")) return "radiusKm";
        if (text.contains("安静") || text.contains("聊天")) return "preference:安静";
        if (text.contains("约会")) return "preference:约会";
        if (text.contains("排队")) return "preference:不排队";
        if (containsAny(text, "菜系", "口味")) return "cuisine";
        for (String cuisine : CuisineCanonicalizer.knownCanonicalValues()) {
            if (text.contains(cuisine)) return "cuisine";
        }
        if (text.contains("店名") || text.contains("关键词")) return "keyword";
        if (text.contains("省份")) return "targetProvince";
        if (text.contains("城市")) return "targetCity";
        if (text.contains("区县") || text.contains("地区")) return "targetDistrict";
        return null;
    }

    private boolean hasMutationSignal(String text, ContextRewriteResult rewrite, boolean hasReference) {
        if (hasReference && hasMutationAnchor(rewrite)) return true;
        // A fact question is fail-closed: budget/price/restaurant mentions are not
        // mutations unless an explicit state-changing operator is present.
        return hasExplicitMutationSignal(text);
    }

    private boolean hasMutationAnchor(ContextRewriteResult rewrite) {
        if (rewrite == null || rewrite.getResolvedReferences() == null) return false;
        for (ResolvedShopReference reference : rewrite.getResolvedReferences()) {
            if (reference != null && reference.intent() != null && reference.intent().isMutationAnchor()) return true;
        }
        return false;
    }

    private boolean hasExplicitMutationSignal(String text) {
        return containsAny(text, "改成", "换成", "调整为", "设置为", "改为", "不吃", "不要", "不用", "去掉",
                "清除", "取消", "不限", "排除", "除了", "太贵", "好贵", "便宜点", "更便宜", "实惠一点",
                "太远", "近一点", "更近", "换一批");
    }

    private Map<String, Object> latestAssistantMessage(List<Map<String, Object>> history) {
        if (history == null) return null;
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, Object> item = history.get(i);
            if (item != null && "assistant".equalsIgnoreCase(String.valueOf(item.get("role")))) return item;
        }
        return null;
    }

    private boolean containsAny(String value, String... terms) {
        if (value == null || terms == null) return false;
        for (String term : terms) if (term != null && !term.isEmpty() && value.contains(term)) return true;
        return false;
    }

    private String normalize(String value) { return value == null ? "" : value.replaceAll("\\s+", ""); }

    private boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }
}
