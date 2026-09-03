package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.util.CuisineCanonicalizer;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ConstraintExtractor {
    private static final Logger log = LoggerFactory.getLogger(ConstraintExtractor.class);
    private static final Pattern BUDGET_PATTERN = Pattern.compile("(?:人均|预算)\\s*(\\d+)");
    private static final Pattern RADIUS_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(公里|km|米|m)", Pattern.CASE_INSENSITIVE);

    @Resource
    private OpenAiCompatibleClient aiClient;
    @Resource
    private ObjectMapper objectMapper;

    public DecisionConstraints extract(String query) {
        try {
            return enforceCurrentDeviceIntent(normalize(extractByModel(query)), query);
        } catch (Exception e) {
            log.warn("[AI][model] action=CONSTRAINT_EXTRACTION event=FALLBACK reason={}", e.getClass().getSimpleName());
            return enforceCurrentDeviceIntent(normalize(extractByRule(query)), query);
        }
    }

    private DecisionConstraints enforceCurrentDeviceIntent(DecisionConstraints constraints, String query) {
        if (!containsCurrentDeviceReference(query)) return constraints;
        constraints.setLocationIntent("CURRENT_DEVICE");
        constraints.setNearby(true);
        constraints.setTargetCity("");
        constraints.setTargetArea("");
        return constraints;
    }

    private DecisionConstraints extractByModel(String query) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", "你是餐饮消费决策需求解析器。只能根据用户原话提取约束；显式目标地点与设备当前位置必须分开：targetCity 是用户要求搜索的城市，targetArea 是用户要求搜索的行政区、商圈或地标，不能把它们放进 keyword。未知值使用空字符串、-1 或 false，不得臆测。"
                + "字段语义：cuisine 只放可枚举菜系（如 川菜/湘菜/粤菜/江浙菜/东北菜/西北菜/闽菜/鲁菜/徽菜/云贵菜/湖北菜/火锅/烧烤/日料/韩餐/西餐/东南亚菜/港式/快餐简餐/面食/粉面/饺子馄饨/小吃/自助餐/海鲜/素食/咖啡/甜品饮品/面包烘焙/其他）；无法归入枚举的明确品类（如 沙县小吃）可保留原名。keyword 只放用户明确点名的实体（具体店名或招牌菜，如 闽师东北菜/锅包肉），不得把菜系放进 keyword。preferences 放开放式的软偏好自然短语（如 安静/不排队/约会/便餐/清淡/辣/适合聚餐/氛围好/性价比高），这是开放集，不要局限于枚举。"
                + "当用户明确放弃或更换之前已存在的某个旧约束时，把该字段名加入 clearedFields（可选字段，例如“看看有没有别的吃的”放弃菜系→cuisine，“预算随便/预算不限”放弃预算→budgetPerPerson）。仅当用户在放弃旧约束时才加入；普通条件微调或新增约束不得加入。"
                + "用户明确取消、放弃或否定的条件，不得放入任何字段（例如“聚会取消了，自己一个人简餐”→ 不得包含 适合聚餐/大桌）。"));
        messages.add(message("user", query));

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "extract_decision_constraints");
        function.put("description", "Extract structured dining or local-consumption decision constraints.");
        function.put("parameters", constraintSchema());
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        JsonNode response = aiClient.chatCompletion(messages, Arrays.asList(tool), null, "CONSTRAINT_EXTRACTION");
        String arguments = response.path("choices").path(0).path("message").path("tool_calls")
                .path(0).path("function").path("arguments").asText();
        if (arguments.trim().isEmpty()) {
            throw new IllegalStateException("模型没有返回结构化约束");
        }
        return objectMapper.readValue(arguments, DecisionConstraints.class);
    }

    private DecisionConstraints extractByRule(String query) {
        DecisionConstraints constraints = new DecisionConstraints();
        if (containsCurrentDeviceReference(query)) constraints.setLocationIntent("CURRENT_DEVICE");
        if (query.contains("日料") || query.contains("寿司")) {
            constraints.setCuisine("日料");
        } else if (query.contains("火锅")) {
            constraints.setCuisine("火锅");
        } else if (query.contains("烧烤") || query.contains("烤肉")) {
            constraints.setCuisine("烧烤");
        } else if (query.contains("西餐") || query.contains("牛排")) {
            constraints.setCuisine("西餐");
        } else if (query.contains("茶餐厅")) {
            constraints.setCuisine("港式");
        }
        Matcher budget = BUDGET_PATTERN.matcher(query);
        if (budget.find()) {
            constraints.setBudgetPerPerson(Integer.valueOf(budget.group(1)));
        }
        Matcher radiusMatcher = RADIUS_PATTERN.matcher(query);
        if (radiusMatcher.find()) {
            double radiusValue = Double.parseDouble(radiusMatcher.group(1));
            String unit = radiusMatcher.group(2);
            constraints.setRadiusKm("米".equals(unit) || "m".equalsIgnoreCase(unit) ? radiusValue / 1000D : radiusValue);
        }
        if (query.contains("安静") || query.contains("聊天")) {
            constraints.getPreferences().add("安静");
        }
        if (query.contains("不想排队") || query.contains("不用排队") || query.contains("少排队")) {
            constraints.getPreferences().add("不排队");
        }
        if (query.contains("约会") || query.contains("女朋友") || query.contains("男朋友")) {
            constraints.getPreferences().add("约会");
        }
        constraints.setNearby(query.contains("附近") || query.contains("周边") || query.contains("就近"));
        if (containsAny(query, "看看有没有别的吃的", "看看有没有别的", "换个别的", "换种", "别的餐厅", "别的店", "别的吃的", "别的菜品", "不要沙县")) {
            if (constraints.getClearedFields() == null) constraints.setClearedFields(new ArrayList<String>());
            if (!constraints.getClearedFields().contains("cuisine")) constraints.getClearedFields().add("cuisine");
            if (!constraints.getClearedFields().contains("keyword")) constraints.getClearedFields().add("keyword");
        }
        Matcher time = Pattern.compile("(?:晚上|晚)\\s*(\\d{1,2}(?::\\d{2})?)?").matcher(query);
        if (time.find()) {
            constraints.setArrivalTime(time.group(1) == null ? "19:00" : normalizeTime(time.group(1)));
        }
        return constraints;
    }

    private DecisionConstraints normalize(DecisionConstraints constraints) {
        if (constraints.getTargetCity() == null) constraints.setTargetCity("");
        if (constraints.getTargetArea() == null) constraints.setTargetArea("");
        constraints.setLocationIntent(normalizeLocationIntent(constraints.getLocationIntent(), constraints));
        if (constraints.getKeyword() == null) constraints.setKeyword("");
        constraints.setCuisine(canonicalizeCuisine(constraints.getCuisine()));
        if (constraints.getBudgetPerPerson() == null) constraints.setBudgetPerPerson(-1);
        if (constraints.getRadiusKm() == null) constraints.setRadiusKm(-1D);
        if (constraints.getNearby() == null) constraints.setNearby(false);
        if (constraints.getArrivalTime() == null) constraints.setArrivalTime("");
        constraints.setPreferences(normalizePreferences(constraints.getPreferences()));
        if (constraints.getSystemNotes() == null) constraints.setSystemNotes(new ArrayList<String>());
        if (constraints.getMissingInformation() == null) constraints.setMissingInformation(new ArrayList<String>());
        if (constraints.getLockedConstraints() == null) constraints.setLockedConstraints(new java.util.HashSet<String>());
        if (constraints.getClearedFields() == null) constraints.setClearedFields(new ArrayList<String>());
        return constraints;
    }

    private String canonicalizeCuisine(String cuisine) {
        return CuisineCanonicalizer.canonicalize(cuisine);
    }

    /** Normalizes open-set preferences into canonical tags where a rule map consumes them; leaves open-ended phrases as-is. */
    private List<String> normalizePreferences(List<String> preferences) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (preferences != null) {
            for (String preference : preferences) {
                if (preference == null || preference.trim().isEmpty()) continue;
                String tag = preference.trim();
                if (containsAny(tag, "情侣", "女朋友", "男朋友", "浪漫", "二人世界")) tag = "约会";
                if (containsAny(tag, "不想排队", "不用排队", "少排队", "避免排队", "别排队")) tag = "不排队";
                if (containsAny(tag, "安静", "静一点", "别太吵", "清净")) tag = "安静";
                if (containsAny(tag, "清淡", "少油", "不油腻", "清爽")) tag = "清淡";
                normalized.add(tag);
            }
        }
        return new ArrayList<>(normalized);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String normalizeLocationIntent(String locationIntent, DecisionConstraints constraints) {
        if ("CURRENT_DEVICE".equalsIgnoreCase(locationIntent)) return "CURRENT_DEVICE";
        if ("EXPLICIT_TARGET".equalsIgnoreCase(locationIntent)
                || hasText(constraints.getTargetCity()) || hasText(constraints.getTargetArea())) return "EXPLICIT_TARGET";
        return "UNSPECIFIED";
    }

    private boolean containsAny(String source, String... values) { for (String value : values) if (source.contains(value)) return true; return false; }

    private boolean containsCurrentDeviceReference(String query) {
        String text = query == null ? "" : query.replaceAll("\\s+", "");
        return text.contains("我附近") || text.contains("我这附近") || text.contains("我身边")
                || text.contains("当前位置") || text.contains("当前定位") || text.contains("现在位置");
    }

    private Map<String, Object> constraintSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("targetCity", property("string", "Explicit target city requested by the user, for example 重庆 or 福州. Empty string if absent."));
        properties.put("targetArea", property("string", "Explicit target district, business area, or landmark, for example 解放碑 or 鼓楼区. Empty string if absent."));
        properties.put("locationIntent", property("string", "EXPLICIT_TARGET for a named destination, CURRENT_DEVICE for the user's current location, or UNSPECIFIED."));
        properties.put("keyword", property("string", "Specific entity the user explicitly names: a restaurant name or signature dish (e.g. 闽师东北菜, 锅包肉). Do NOT put a cuisine here — cuisines go to the cuisine field. Do not include targetCity or targetArea."));
        properties.put("cuisine", property("string", "Cuisine category (e.g. 川菜, 火锅, 日料, 快餐简餐, 面食, 小吃). Empty string if unknown."));
        properties.put("budgetPerPerson", property("integer", "Maximum per-person budget. -1 if unknown."));
        properties.put("radiusKm", property("number", "Search radius in kilometers. -1 if unknown."));
        properties.put("nearby", property("boolean", "Whether the user uses a nearby/local intent."));
        properties.put("arrivalTime", property("string", "Arrival time HH:mm. Empty string if unknown."));
        properties.put("preferences", arrayProperty("Open-set soft preferences as natural-language tags, e.g. 安静, 不排队, 约会, 便餐, 清淡, 辣, 适合聚餐, 氛围好, 性价比高. This is an open set — do not restrict to an enum. Do NOT include constraints the user explicitly cancelled or negated."));
        properties.put("missingInformation", arrayProperty("Information needed but not supplied."));
        properties.put("clearedFields", arrayProperty("Constraint fields the user explicitly abandons: cuisine, keyword, budgetPerPerson, radiusKm, nearby, targetCity, targetArea, arrivalTime, preferences. Empty array if none."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new ArrayList<>(properties.keySet()));
        schema.put("additionalProperties", false);
        return schema;
    }

    private Map<String, Object> property(String type, String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", type);
        property.put("description", description);
        return property;
    }

    private Map<String, Object> arrayProperty(String description) {
        Map<String, Object> property = property("array", description);
        property.put("items", property("string", "A concise constraint."));
        return property;
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private String normalizeTime(String input) {
        return input.length() <= 2 ? (input.length() == 1 ? "0" + input : input) + ":00" : input;
    }
}
