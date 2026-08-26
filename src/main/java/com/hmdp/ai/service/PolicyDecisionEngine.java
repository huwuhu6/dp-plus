package com.hmdp.ai.service;

import com.hmdp.ai.dto.ConversationLocationSlot;
import com.hmdp.ai.dto.ConversationWorkingMemory;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.DecisionRequest;
import com.hmdp.ai.dto.PolicyDecision;
import org.springframework.stereotype.Service;

/**
 * A deterministic policy gate. It deliberately has no model or tool dependency so every
 * clarification and execution choice is reproducible in tests and logs.
 */
@Service
public class PolicyDecisionEngine {
    public static final String RESOLVE_EXPLICIT_LOCATION = "RESOLVE_EXPLICIT_LOCATION";
    public static final String CLARIFY_LOCATION = "CLARIFY_LOCATION";
    public static final String EXECUTE_RECOMMENDATION = "EXECUTE_RECOMMENDATION";
    public static final String SHOP_FACT = "SHOP_FACT";
    public static final String SHOP_VOUCHER = "SHOP_VOUCHER";
    public static final String SHOP_EVIDENCE = "SHOP_EVIDENCE";
    public static final String COMPARE_SHOPS = "COMPARE_SHOPS";

    public PolicyDecision decideRecommendation(DecisionRequest request, DecisionConstraints constraints,
                                               ConversationWorkingMemory memory) {
        if (constraints != null && (hasText(constraints.getTargetCity()) || hasText(constraints.getTargetArea()))) {
            return PolicyDecision.of(EXECUTE_RECOMMENDATION,
                    "已获得用户显式指定的目标地点，禁止使用设备定位覆盖");
        }
        if (memory != null && hasNamedSearchLocation(memory.getSearchLocation())) {
            return PolicyDecision.of(EXECUTE_RECOMMENDATION,
                    "已获得会话中持久化的显式目标地点");
        }
        if (constraints != null && hasText(constraints.getTargetCity())) {
            return PolicyDecision.of(EXECUTE_RECOMMENDATION, "已获得用户明确指定的目标城市");
        }
        if (memory != null && memory.getSearchLocation() != null && hasText(memory.getSearchLocation().getCity())) {
            return PolicyDecision.of(EXECUTE_RECOMMENDATION, "复用用户明确指定的目标城市");
        }
        if (hasCoordinates(request)) return PolicyDecision.of(EXECUTE_RECOMMENDATION, "已获得有效搜索地理锚点");
        if (memory != null && hasCoordinates(memory.getSearchLocation())) {
            return PolicyDecision.of(EXECUTE_RECOMMENDATION, "复用已确认的搜索目标位置");
        }
        if ((Boolean.TRUE.equals(constraints == null ? null : constraints.getNearby())
                || "CURRENT_DEVICE".equals(constraints == null ? null : constraints.getLocationIntent()))
                && memory != null && hasCoordinates(memory.getLocation())) {
            return PolicyDecision.of(EXECUTE_RECOMMENDATION, "复用用户授权的设备定位");
        }
        PolicyDecision decision = PolicyDecision.of(CLARIFY_LOCATION, "餐饮推荐缺少地理锚点");
        decision.setBlocking(true);
        return decision;
    }

    public PolicyDecision decideFollowUp(String message) {
        String text = message == null ? "" : message;
        if (containsAny(text, "比较", "对比", "哪个更")) return PolicyDecision.of(COMPARE_SHOPS, "多商户事实对比");
        if (containsAny(text, "优惠", "券", "团购")) return PolicyDecision.of(SHOP_VOUCHER, "单店优惠券查询");
        if (containsAny(text, "评价", "评论", "口碑", "环境", "排队")) return PolicyDecision.of(SHOP_EVIDENCE, "单店评价证据查询");
        return PolicyDecision.of(SHOP_FACT, "单店基础事实查询");
    }

    private boolean hasCoordinates(DecisionRequest request) {
        return request != null && request.getLatitude() != null && request.getLongitude() != null;
    }

    private boolean hasCoordinates(ConversationLocationSlot location) {
        return location != null && "AVAILABLE".equals(location.getStatus())
                && location.getLatitude() != null && location.getLongitude() != null;
    }

    private boolean hasNamedSearchLocation(ConversationLocationSlot location) {
        return location != null && (hasText(location.getCity()) || hasText(location.getDistrict()));
    }

    private boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }
    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }
}
