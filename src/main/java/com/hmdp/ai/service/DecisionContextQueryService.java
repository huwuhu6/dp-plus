package com.hmdp.ai.service;

import com.hmdp.ai.dto.ConstraintSource;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.DecisionContextFacts;
import com.hmdp.ai.dto.DecisionContextQuery;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.dto.DecisionTaskState;
import com.hmdp.ai.dto.ConversationWorkingMemory;
import com.hmdp.ai.entity.AiChatSession;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only, grounded queries over canonical state and persisted decision facts. */
@Service
public class DecisionContextQueryService {
    @Resource private ConversationStateService conversationStateService;
    @Resource private ConsumptionDecisionService decisionService;

    public QueryResult execute(AiChatSession state, DecisionContextQuery query) {
        if (query == null || query.getType() == null) {
            return new QueryResult("目前无法确定你要查询哪一部分决策信息。", new DecisionContextFacts());
        }
        return switch (query.getType()) {
            case WHY_RECOMMENDED -> explainRecommendation(state, query);
            case CONSTRAINT_PROVENANCE -> explainConstraint(state, query);
            case CURRENT_CRITERIA -> explainCurrentCriteria(state);
        };
    }

    private QueryResult explainRecommendation(AiChatSession state, DecisionContextQuery query) {
        DecisionContextFacts facts = new DecisionContextFacts();
        if (query.getResolvedReference() == null || query.getResolvedReference().batch() == null
                || query.getResolvedReference().shopId() == null
                || query.getResolvedReference().batch().getDecisionSessionId() == null) {
            return new QueryResult("目前还没有可解释的推荐结果，或我无法确定你指的是哪一家。你可以先让我推荐几家餐厅。", facts);
        }
        Long sessionId = query.getResolvedReference().batch().getDecisionSessionId();
        DecisionResponse decision = decisionService.getDecision(sessionId);
        DecisionRecommendation recommendation = findRecommendation(decision, query.getResolvedReference().shopId());
        facts.setDecisionSessionId(sessionId);
        facts.setShopId(query.getResolvedReference().shopId());
        facts.setShopName(recommendation == null ? query.getResolvedReference().shopName() : recommendation.getShopName());
        if (recommendation == null) {
            return new QueryResult("当时的推荐记录里没有找到这家店的完整事实，因此我不能可靠说明它为什么被推荐。", facts);
        }
        facts.setAvgPrice(recommendation.getAvgPrice());
        facts.setDistanceKm(recommendation.getDistanceKm());
        facts.setMatchedReasons(copy(recommendation.getMatchedReasons()));
        facts.setEvidence(copy(recommendation.getEvidence()));
        List<String> reasons = new ArrayList<String>();
        reasons.addAll(facts.getMatchedReasons());
        for (String evidence : facts.getEvidence()) if (!reasons.contains(evidence)) reasons.add(evidence);
        if (reasons.isEmpty()) {
            return new QueryResult("当时保存的推荐结果没有记录足够的匹配理由，我不能根据当前条件重新猜测。", facts);
        }
        StringBuilder answer = new StringBuilder("当时推荐“").append(facts.getShopName()).append("”主要因为：");
        answer.append(String.join("；", limit(reasons, 3))).append("。");
        return new QueryResult(answer.toString(), facts);
    }

    private QueryResult explainConstraint(AiChatSession state, DecisionContextQuery query) {
        DecisionContextFacts facts = snapshotFacts(state);
        String key = query.getConstraintKey();
        facts.setConstraintKey(key);
        if (key == null || key.trim().isEmpty()) {
            return new QueryResult("请告诉我你想核对预算、距离、地点或哪一项偏好。", facts);
        }
        DecisionConstraints criteria = facts.getCurrentCriteria();
        String value = valueOf(criteria, key);
        facts.setConstraintValue(value);
        ConstraintSource source = facts.getConstraintSources() == null ? null : facts.getConstraintSources().get(key);
        facts.setSource(source);
        if (value == null || value.isEmpty() || "-1".equals(value) || "false".equals(value)) {
            String label = key.startsWith("preference:") ? displayKey(key) : "“" + displayKey(key) + "”";
            return new QueryResult("当前条件里没有生效的" + label + "。", facts);
        }
        if (key.startsWith("preference:") && source == null) {
            return new QueryResult("当前记录中存在“" + key.substring("preference:".length())
                    + "”这个条件，但旧状态没有保存它的来源，所以我不能确认这是你明确提出的还是系统推导的。", facts);
        }
        String label = displayKey(key);
        boolean preference = key.startsWith("preference:");
        if (source == ConstraintSource.USER_EXPLICIT) {
            return new QueryResult(preference ? "当前" + label + "，这是你明确提出的。"
                    : "当前" + label + "是" + value + "，这个值是你明确提出的。", facts);
        }
        if (source == ConstraintSource.DERIVED) {
            return new QueryResult(preference ? "当前" + label + "，这是系统根据上下文推导出来的，不是你直接提出的。"
                    : "当前" + label + "是" + value + "，这是系统根据上下文推导出来的，不是你直接给出的数字或条件。", facts);
        }
        if (source == ConstraintSource.SYSTEM_DEFAULT) {
            return new QueryResult(preference ? "当前" + label + "，这是系统默认补充的条件，不是你明确提出的。"
                    : "当前" + label + "是" + value + "，这是系统默认补充的条件，不是你明确提出的。", facts);
        }
        return new QueryResult("当前记录中有" + label + "=" + value + "，但没有保存它的来源，我不能确认是否由你明确提出。", facts);
    }

    private QueryResult explainCurrentCriteria(AiChatSession state) {
        DecisionContextFacts facts = snapshotFacts(state);
        DecisionConstraints criteria = facts.getCurrentCriteria();
        if (criteria == null) return new QueryResult("当前还没有生效的餐饮决策条件。", facts);
        List<String> values = new ArrayList<String>();
        if (hasText(criteria.getTargetProvince())) values.add("省份=" + criteria.getTargetProvince());
        if (hasText(criteria.getTargetCity())) values.add("城市=" + criteria.getTargetCity());
        if (hasText(criteria.getTargetDistrict())) values.add("区县=" + criteria.getTargetDistrict());
        if (hasText(criteria.getTargetArea())) values.add("区域=" + criteria.getTargetArea());
        if (hasText(criteria.getCuisine())) values.add("菜系=" + criteria.getCuisine());
        if (criteria.getBudgetPerPerson() != null && criteria.getBudgetPerPerson() > 0) values.add("人均预算=" + criteria.getBudgetPerPerson() + "元");
        if (criteria.getRadiusKm() != null && criteria.getRadiusKm() > 0) values.add("距离=" + criteria.getRadiusKm() + "公里");
        if (criteria.getPreferences() != null) for (String preference : criteria.getPreferences()) values.add("偏好=" + preference);
        return new QueryResult(values.isEmpty() ? "当前还没有生效的餐饮决策条件。" : "当前生效条件是：" + String.join("；", values) + "。", facts);
    }

    private DecisionContextFacts snapshotFacts(AiChatSession state) {
        DecisionContextFacts facts = new DecisionContextFacts();
        ConversationWorkingMemory memory = conversationStateService.workingMemory(state);
        DecisionTaskState task = conversationStateService.activeTask(memory);
        facts.setCurrentCriteria(task == null ? null : task.getCriteria());
        Map<String, ConstraintSource> sources = task == null || task.getConstraintSources() == null
                ? new LinkedHashMap<String, ConstraintSource>() : new LinkedHashMap<String, ConstraintSource>(task.getConstraintSources());
        facts.setConstraintSources(sources);
        return facts;
    }

    private DecisionRecommendation findRecommendation(DecisionResponse decision, Long shopId) {
        if (decision == null || decision.getRecommendations() == null) return null;
        for (DecisionRecommendation item : decision.getRecommendations()) {
            if (item != null && shopId != null && shopId.equals(item.getShopId())) return item;
        }
        return null;
    }

    private String valueOf(DecisionConstraints criteria, String key) {
        if (criteria == null || key == null) return "";
        if (key.startsWith("preference:")) {
            String value = key.substring("preference:".length());
            return criteria.getPreferences() != null && criteria.getPreferences().contains(value) ? value : "";
        }
        return switch (key) {
            case "budgetPerPerson" -> String.valueOf(criteria.getBudgetPerPerson());
            case "radiusKm" -> String.valueOf(criteria.getRadiusKm());
            case "nearby" -> String.valueOf(criteria.getNearby());
            case "targetProvince" -> criteria.getTargetProvince();
            case "targetCity" -> criteria.getTargetCity();
            case "targetDistrict" -> criteria.getTargetDistrict();
            case "targetArea" -> criteria.getTargetArea();
            case "cuisine" -> criteria.getCuisine();
            case "keyword" -> criteria.getKeyword();
            default -> "";
        };
    }

    private String displayKey(String key) {
        if (key == null) return "条件";
        if (key.startsWith("preference:")) return "偏好“" + key.substring("preference:".length()) + "”";
        return switch (key) {
            case "budgetPerPerson" -> "人均预算";
            case "radiusKm" -> "距离范围";
            case "nearby" -> "附近条件";
            case "targetProvince" -> "目标省份";
            case "targetCity" -> "目标城市";
            case "targetDistrict" -> "目标区县";
            case "targetArea" -> "目标区域";
            case "cuisine" -> "菜系";
            case "keyword" -> "指定关键词";
            default -> key;
        };
    }

    private List<String> copy(List<String> values) { return values == null ? new ArrayList<String>() : new ArrayList<String>(values); }

    private List<String> limit(List<String> values, int max) { return values.size() <= max ? values : new ArrayList<String>(values.subList(0, max)); }

    private boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }

    public record QueryResult(String answer, DecisionContextFacts facts) { }
}
