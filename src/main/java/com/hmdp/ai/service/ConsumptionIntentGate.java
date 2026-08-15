package com.hmdp.ai.service;

import java.util.Arrays;
import java.util.List;

/**
 * Keeps general chat out of the recommendation and business-tool workflows.
 */
final class ConsumptionIntentGate {
    private static final List<String> CAPABILITY_TERMS = Arrays.asList(
            "你是谁", "你是?", "你是？", "你能做什么", "你会什么", "怎么用", "帮助");
    private static final List<String> CONSUMPTION_TERMS = Arrays.asList(
            "吃", "饭", "餐", "美食", "菜", "店", "约会", "预算", "人均", "附近", "周边", "推荐",
            "评价", "评论", "优惠券", "优惠", "排队", "环境", "口味", "火锅", "烧烤", "日料", "寿司",
            "西餐", "牛排", "咖啡", "奶茶", "小吃", "宵夜", "午饭", "晚饭", "早餐");

    enum Intent {
        CONSUMPTION,
        CAPABILITY,
        OUT_OF_SCOPE
    }

    private ConsumptionIntentGate() {
    }

    static Intent classify(String message) {
        String normalized = message == null ? "" : message.trim().toLowerCase();
        if (containsAny(normalized, CAPABILITY_TERMS)) return Intent.CAPABILITY;
        return containsAny(normalized, CONSUMPTION_TERMS) ? Intent.CONSUMPTION : Intent.OUT_OF_SCOPE;
    }

    static String reply(Intent intent) {
        if (intent == Intent.CAPABILITY) {
            return "我是点评消费决策助手，可以根据用餐场景、预算、位置和偏好推荐商户；推荐完成后还可以查询优惠券、评价、备选和商户对比。";
        }
        return "我目前只处理本地餐饮消费决策和已推荐商户的事实查询。你可以告诉我想吃什么、预算、位置或用餐场景。";
    }

    private static boolean containsAny(String source, List<String> terms) {
        for (String term : terms) {
            if (source.contains(term)) return true;
        }
        return false;
    }
}
