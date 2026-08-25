package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.dto.DecisionConstraints;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
            return normalize(extractByModel(query));
        } catch (Exception e) {
            log.warn("[AI][model] action=CONSTRAINT_EXTRACTION event=FALLBACK reason={}", e.getClass().getSimpleName());
            return normalize(extractByRule(query));
        }
    }

    private DecisionConstraints extractByModel(String query) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", "你是消费决策需求解析器。只能根据用户原话提取约束；未知值使用空字符串、-1 或 false，不得臆测。地点必须严格拆分：targetCity 仅填用户明确指定的目标城市，targetArea 仅填目标行政区、商圈或地标，绝不能将地点填入 cuisine、keyword 或 hardConstraints；用户设备当前位置不属于 targetCity。keyword 仅填商户名或核心餐饮检索词，不含城市、区域或‘附近’等地理范围词。"));
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
        if (query.contains("日料") || query.contains("寿司")) {
            constraints.setCuisine("日料");
        } else if (query.contains("火锅")) {
            constraints.setCuisine("火锅");
        } else if (query.contains("烧烤")) {
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
        constraints.setQuiet(query.contains("安静") || query.contains("聊天"));
        constraints.setAvoidQueue(query.contains("不想排队") || query.contains("不用排队") || query.contains("少排队"));
        constraints.setNearby(query.contains("附近") || query.contains("周边") || query.contains("就近"));
        if (query.contains("约会") || query.contains("女朋友") || query.contains("男朋友")) {
            constraints.setOccasion("约会");
        }
        Matcher time = Pattern.compile("(?:晚上|晚)\\s*(\\d{1,2}(?::\\d{2})?)?").matcher(query);
        if (time.find()) {
            constraints.setArrivalTime(time.group(1) == null ? "19:00" : normalizeTime(time.group(1)));
        }
        return constraints;
    }

    private DecisionConstraints normalize(DecisionConstraints constraints) {
        constraints.setTargetCity(normalizeText(constraints.getTargetCity()));
        constraints.setTargetArea(normalizeText(constraints.getTargetArea()));
        constraints.setKeyword(normalizeText(constraints.getKeyword()));
        constraints.setCuisine(canonicalizeCuisine(constraints.getCuisine()));
        if (constraints.getBudgetPerPerson() == null) constraints.setBudgetPerPerson(-1);
        if (constraints.getRadiusKm() == null) constraints.setRadiusKm(-1D);
        if (constraints.getNearby() == null) constraints.setNearby(false);
        if (constraints.getArrivalTime() == null) constraints.setArrivalTime("");
        constraints.setOccasion(canonicalizeOccasion(constraints.getOccasion()));
        if (constraints.getQuiet() == null) constraints.setQuiet(false);
        if (constraints.getAvoidQueue() == null) constraints.setAvoidQueue(false);
        if (constraints.getHardConstraints() == null) constraints.setHardConstraints(new ArrayList<String>());
        if (constraints.getSoftPreferences() == null) constraints.setSoftPreferences(new ArrayList<String>());
        if (constraints.getMissingInformation() == null) constraints.setMissingInformation(new ArrayList<String>());
        return constraints;
    }

    private String canonicalizeCuisine(String cuisine) {
        if (cuisine == null || cuisine.trim().isEmpty()) return "";
        String normalized = cuisine.trim();
        if (normalized.contains("日料") || normalized.contains("寿司")) return "日料";
        if (normalized.contains("火锅")) return "火锅";
        if (normalized.contains("烧烤") || normalized.contains("烤肉")) return "烧烤";
        if (normalized.contains("西餐") || normalized.contains("牛排")) return "西餐";
        if (normalized.contains("港式") || normalized.contains("茶餐厅")) return "港式";
        return normalized;
    }

    private String canonicalizeOccasion(String occasion) {
        if (occasion == null || occasion.trim().isEmpty()) return "";
        String normalized = occasion.trim();
        if (normalized.contains("约会") || normalized.contains("情侣") || normalized.contains("女朋友") || normalized.contains("男朋友")) return "约会";
        return normalized;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private Map<String, Object> constraintSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("targetCity", property("string", "用户显式要求搜索的目标城市名称，如重庆、北京。若未提及留空。不得填设备当前位置。"));
        properties.put("targetArea", property("string", "用户显式要求搜索的目标区域、商圈或地标，如解放碑、朝阳区。若未提及留空。"));
        properties.put("keyword", property("string", "特定店铺名称或核心品类词；不包含城市、区域、附近等地理范围词。未知时留空。"));
        properties.put("cuisine", property("string", "Cuisine, such as 日料. Empty string if unknown."));
        properties.put("budgetPerPerson", property("integer", "Maximum per-person budget. -1 if unknown."));
        properties.put("radiusKm", property("number", "Search radius in kilometers. -1 if unknown."));
        properties.put("nearby", property("boolean", "Whether the user uses a nearby/local intent."));
        properties.put("arrivalTime", property("string", "Arrival time HH:mm. Empty string if unknown."));
        properties.put("occasion", property("string", "Occasion. Empty string if unknown."));
        properties.put("quiet", property("boolean", "Whether quiet ambience is requested."));
        properties.put("avoidQueue", property("boolean", "Whether avoiding queues is requested."));
        properties.put("hardConstraints", arrayProperty("Hard constraints explicitly stated by user."));
        properties.put("softPreferences", arrayProperty("Soft preferences explicitly stated by user."));
        properties.put("missingInformation", arrayProperty("Information needed but not supplied."));
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
