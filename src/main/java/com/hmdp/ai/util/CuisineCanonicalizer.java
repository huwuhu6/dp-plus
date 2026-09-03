package com.hmdp.ai.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Canonicalizes cuisine expressions to a shared canonical value so that
 * user-side and merchant-side expressions map to the same key.
 * <p>
 * Design principles:
 * <ul>
 *   <li>Closed set of explicit mappings — no ontology, no NER, no external knowledge base.</li>
 *   <li>Exact match only — no substring / contains-based fallback in the canonicalizer itself.</li>
 *   <li>Both sides (user query and merchant profile) use the same canonicalization.</li>
 *   <li>After canonicalization, comparison is {@link String#equals(Object)}.</li>
 * </ul>
 * <p>
 * Merchant profile cuisine is a comma-separated string (e.g. {@code "日料,寿司"} or
 * {@code "港式茶餐厅"}). The caller is responsible for splitting by comma and
 * canonicalizing each token individually before comparing.
 * <p>
 * The canonical set follows mainstream meal-delivery / review platforms (Meituan /
 * Dianping app top-level categories): local cuisines (川湘粤...), hotpot/BBQ,
 * foreign cuisines, fast food / noodles / snacks, plus drinks/dessert/bakery and an
 * "其他" fallback (Rasa "other" principle for categorical slots). It is deliberately
 * one level of granularity — no 川式火锅/粤式火锅 sub-splits — so the set is complete
 * enough for common requests without exploding.
 */
public class CuisineCanonicalizer {

    private static final Map<String, String> CANONICAL_MAP = new LinkedHashMap<>();

    static {
        // --- Japanese ---
        CANONICAL_MAP.put("日料", "日料");
        CANONICAL_MAP.put("日本料理", "日料");
        CANONICAL_MAP.put("日式料理", "日料");
        CANONICAL_MAP.put("日本菜", "日料");
        CANONICAL_MAP.put("日式", "日料");
        CANONICAL_MAP.put("寿司", "日料");
        CANONICAL_MAP.put("刺身", "日料");

        // --- BBQ / Grill ---
        CANONICAL_MAP.put("烧烤", "烧烤");
        CANONICAL_MAP.put("烤肉", "烧烤");
        CANONICAL_MAP.put("烤串", "烧烤");
        CANONICAL_MAP.put("铁板烧", "烧烤");

        // --- Western ---
        CANONICAL_MAP.put("西餐", "西餐");
        CANONICAL_MAP.put("牛排", "西餐");
        CANONICAL_MAP.put("意大利菜", "西餐");
        CANONICAL_MAP.put("法餐", "西餐");
        CANONICAL_MAP.put("西式", "西餐");
        CANONICAL_MAP.put("披萨", "西餐");
        CANONICAL_MAP.put("意面", "西餐");

        // --- Hong Kong style ---
        CANONICAL_MAP.put("港式", "港式");
        CANONICAL_MAP.put("茶餐厅", "港式");
        CANONICAL_MAP.put("港式茶餐厅", "港式");
        CANONICAL_MAP.put("港式点心", "港式");

        // --- Hot pot ---
        CANONICAL_MAP.put("火锅", "火锅");
        CANONICAL_MAP.put("串串香", "火锅");
        CANONICAL_MAP.put("涮肉", "火锅");
        CANONICAL_MAP.put("涮羊肉", "火锅");

        // --- Chinese local cuisines ---
        CANONICAL_MAP.put("川菜", "川菜");
        CANONICAL_MAP.put("四川菜", "川菜");
        CANONICAL_MAP.put("麻辣", "川菜");
        CANONICAL_MAP.put("水煮鱼", "川菜");
        CANONICAL_MAP.put("湘菜", "湘菜");
        CANONICAL_MAP.put("湖南菜", "湘菜");
        CANONICAL_MAP.put("剁椒", "湘菜");
        CANONICAL_MAP.put("粤菜", "粤菜");
        CANONICAL_MAP.put("广东菜", "粤菜");
        CANONICAL_MAP.put("广式", "粤菜");
        CANONICAL_MAP.put("早茶", "粤菜");
        CANONICAL_MAP.put("江浙菜", "江浙菜");
        CANONICAL_MAP.put("本帮菜", "江浙菜");
        CANONICAL_MAP.put("杭帮菜", "江浙菜");
        CANONICAL_MAP.put("上海菜", "江浙菜");
        CANONICAL_MAP.put("淮扬菜", "江浙菜");
        CANONICAL_MAP.put("东北菜", "东北菜");
        CANONICAL_MAP.put("西北菜", "西北菜");
        CANONICAL_MAP.put("陕西菜", "西北菜");
        CANONICAL_MAP.put("闽菜", "闽菜");
        CANONICAL_MAP.put("福建菜", "闽菜");
        CANONICAL_MAP.put("鲁菜", "鲁菜");
        CANONICAL_MAP.put("山东菜", "鲁菜");
        CANONICAL_MAP.put("徽菜", "徽菜");
        CANONICAL_MAP.put("云贵菜", "云贵菜");
        CANONICAL_MAP.put("云南菜", "云贵菜");
        CANONICAL_MAP.put("贵州菜", "云贵菜");
        CANONICAL_MAP.put("湖北菜", "湖北菜");

        // --- Foreign cuisines (beyond Japanese/Western) ---
        CANONICAL_MAP.put("韩餐", "韩餐");
        CANONICAL_MAP.put("韩国料理", "韩餐");
        CANONICAL_MAP.put("韩式", "韩餐");
        CANONICAL_MAP.put("东南亚菜", "东南亚菜");
        CANONICAL_MAP.put("泰国菜", "东南亚菜");
        CANONICAL_MAP.put("越南菜", "东南亚菜");
        CANONICAL_MAP.put("冬阴功", "东南亚菜");

        // --- Fast food / noodles / snacks ---
        CANONICAL_MAP.put("快餐", "快餐简餐");
        CANONICAL_MAP.put("便当", "快餐简餐");
        CANONICAL_MAP.put("简餐", "快餐简餐");
        CANONICAL_MAP.put("汉堡", "快餐简餐");
        CANONICAL_MAP.put("炸鸡", "快餐简餐");
        CANONICAL_MAP.put("盖浇饭", "快餐简餐");
        CANONICAL_MAP.put("盒饭", "快餐简餐");
        CANONICAL_MAP.put("面食", "面食");
        CANONICAL_MAP.put("面条", "面食");
        CANONICAL_MAP.put("拉面", "面食");
        CANONICAL_MAP.put("兰州拉面", "面食");
        CANONICAL_MAP.put("重庆小面", "面食");
        CANONICAL_MAP.put("拌面", "面食");
        CANONICAL_MAP.put("热干面", "面食");
        CANONICAL_MAP.put("米粉", "粉面");
        CANONICAL_MAP.put("螺蛳粉", "粉面");
        CANONICAL_MAP.put("桂林米粉", "粉面");
        CANONICAL_MAP.put("过桥米线", "粉面");
        CANONICAL_MAP.put("米线", "粉面");
        CANONICAL_MAP.put("河粉", "粉面");
        CANONICAL_MAP.put("饺子", "饺子馄饨");
        CANONICAL_MAP.put("馄饨", "饺子馄饨");
        CANONICAL_MAP.put("包子", "饺子馄饨");
        CANONICAL_MAP.put("生煎", "饺子馄饨");
        CANONICAL_MAP.put("锅贴", "饺子馄饨");
        CANONICAL_MAP.put("小吃", "小吃");
        CANONICAL_MAP.put("沙县小吃", "小吃");
        CANONICAL_MAP.put("卤味", "小吃");
        CANONICAL_MAP.put("凉皮", "小吃");
        CANONICAL_MAP.put("肉夹馍", "小吃");
        CANONICAL_MAP.put("麻辣烫", "小吃");
        CANONICAL_MAP.put("煎饼果子", "小吃");
        CANONICAL_MAP.put("关东煮", "小吃");

        // --- Other meal types ---
        CANONICAL_MAP.put("自助餐", "自助餐");
        CANONICAL_MAP.put("自助", "自助餐");
        CANONICAL_MAP.put("海鲜", "海鲜");
        CANONICAL_MAP.put("小龙虾", "海鲜");
        CANONICAL_MAP.put("大闸蟹", "海鲜");
        CANONICAL_MAP.put("生蚝", "海鲜");
        CANONICAL_MAP.put("素食", "素食");
        CANONICAL_MAP.put("斋菜", "素食");
        CANONICAL_MAP.put("轻食", "素食");
        CANONICAL_MAP.put("沙拉", "素食");

        // --- Drinks / dessert / bakery ---
        CANONICAL_MAP.put("咖啡", "咖啡");
        CANONICAL_MAP.put("咖啡厅", "咖啡");
        CANONICAL_MAP.put("咖啡馆", "咖啡");
        CANONICAL_MAP.put("甜品", "甜品饮品");
        CANONICAL_MAP.put("奶茶", "甜品饮品");
        CANONICAL_MAP.put("饮品", "甜品饮品");
        CANONICAL_MAP.put("冰淇淋", "甜品饮品");
        CANONICAL_MAP.put("糖水", "甜品饮品");
        CANONICAL_MAP.put("水果捞", "甜品饮品");
        CANONICAL_MAP.put("面包", "面包烘焙");
        CANONICAL_MAP.put("烘焙", "面包烘焙");
        CANONICAL_MAP.put("蛋糕", "面包烘焙");
        CANONICAL_MAP.put("糕点", "面包烘焙");

        // --- Fallback (Rasa "other" principle: categorical slot must have a catch-all) ---
        CANONICAL_MAP.put("其他", "其他");
        CANONICAL_MAP.put("创意融合菜", "其他");
        CANONICAL_MAP.put("融合菜", "其他");
    }

    private CuisineCanonicalizer() {
        // utility class
    }

    /**
     * Returns the canonical form of {@code cuisine}, or the input itself if no
     * mapping exists. Never returns null.
     *
     * @param cuisine a cuisine expression (may be null, empty, or whitespace)
     * @return the canonical form, or the trimmed input if unmapped
     */
    public static String canonicalize(String cuisine) {
        if (cuisine == null || cuisine.isBlank()) {
            return "";
        }
        String trimmed = cuisine.trim();
        String canonical = CANONICAL_MAP.get(trimmed);
        return canonical != null ? canonical : trimmed;
    }

    /**
     * Returns the set of canonical cuisine values known to this canonicalizer.
     * Useful for diagnostics and testing.
     */
    public static java.util.Set<String> knownCanonicalValues() {
        return new java.util.LinkedHashSet<>(CANONICAL_MAP.values());
    }
}
