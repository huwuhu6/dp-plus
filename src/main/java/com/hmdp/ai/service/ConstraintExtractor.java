package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.ConstraintSource;
import com.hmdp.ai.geo.AdministrativeRegion;
import com.hmdp.ai.geo.AdministrativeRegionResolver;
import com.hmdp.ai.geo.AdministrativeResolution;
import com.hmdp.ai.util.CuisineCanonicalizer;
import com.hmdp.ai.util.PreferenceCanonicalizer;
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
    private static final Pattern EXCLUDED_CUISINE_PATTERN = Pattern.compile("除了\\s*([\\p{IsHan}]{2,12}(?:菜|料理))(?:之外|以外|都|也|应该|可以|，|,|$)");

    @Resource
    private OpenAiCompatibleClient aiClient;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private AdministrativeRegionResolver administrativeRegionResolver;

    public DecisionConstraints extract(String query) {
        return extract(query, null);
    }

    public DecisionConstraints extract(String query, DecisionConstraints locationContext) {
        AdministrativeResolution regionResolution = resolver().resolve(query, locationContext);
        DecisionConstraints constraints;
        try {
            constraints = normalize(extractByModel(query));
        } catch (Exception e) {
            log.warn("[AI][model] action=CONSTRAINT_EXTRACTION event=FALLBACK reason={}", e.getClass().getSimpleName());
            constraints = normalize(extractByRule(query));
        }
        // Model administrative fields are candidate hints only.  They may be used to
        // ask the deterministic authority for validation, but never become canonical
        // state merely because the model filled a slot.
        if (regionResolution != null && regionResolution.status() != AdministrativeResolution.Status.RESOLVED
                && hasAdministrativeHint(constraints)) {
            AdministrativeResolution hinted = resolver().resolveHint(query, constraints, locationContext);
            if (hinted.status() != AdministrativeResolution.Status.NOT_FOUND) regionResolution = hinted;
        }
        mergeAdministrativeResolution(constraints, regionResolution);
        constraints = enforceCurrentDeviceIntent(applyMutations(applyDirectionFallback(
                applySemanticLocationFallback(constraints, query), query), query), query);
        applyExcludedCuisine(constraints, query);
        assignPreferenceSourceHints(constraints, query);
        return constraints;
    }

    /** Source is decided while interpreting the user turn, never guessed by the state reducer. */
    private void assignPreferenceSourceHints(DecisionConstraints constraints, String query) {
        if (constraints == null || constraints.getPreferences() == null) return;
        String text = query == null ? "" : query.replaceAll("\\s+", "");
        boolean derivedDating = !text.contains("约会")
                && (text.contains("女朋友") || text.contains("男朋友") || text.contains("情侣"));
        if (text.contains("聊天") && !text.contains("安静")) {
            constraints.getPreferences().removeIf(item -> item != null && item.contains("聊天"));
            if (!constraints.getPreferences().contains("安静")) constraints.getPreferences().add("安静");
        }
        for (String preference : constraints.getPreferences()) {
            if (preference == null || preference.isBlank()) continue;
            ConstraintSource source = ConstraintSource.USER_EXPLICIT;
            if ("安静".equals(preference) && text.contains("聊天") && !text.contains("安静")) {
                source = ConstraintSource.DERIVED;
            }
            if ("约会".equals(preference) && derivedDating) {
                source = ConstraintSource.DERIVED;
            }
            constraints.getSourceHints().put("preference:" + preference, source);
        }
    }

    private void mergeAdministrativeResolution(DecisionConstraints constraints, AdministrativeResolution resolution) {
        if (resolution == null) return;
        if (resolution.status() == AdministrativeResolution.Status.AMBIGUOUS) {
            clearUnverifiedAdministrativeHint(constraints);
            return;
        }
        if (resolution.status() != AdministrativeResolution.Status.RESOLVED || resolution.candidates().isEmpty()) {
            if (hasAdministrativeHint(constraints)) clearUnverifiedAdministrativeHint(constraints);
            return;
        }
        AdministrativeRegion region = resolution.candidates().get(0);
        if (region.getLevel() == com.hmdp.ai.geo.AdministrativeLevel.PROVINCE) {
            constraints.setTargetProvince(region.getProvince());
            constraints.setTargetCity("");
            constraints.setTargetDistrict("");
        } else if (region.getLevel() == com.hmdp.ai.geo.AdministrativeLevel.CITY) {
            constraints.setTargetProvince(region.getProvince());
            constraints.setTargetCity(region.getCity());
            constraints.setTargetDistrict("");
        } else {
            constraints.setTargetProvince(region.getProvince());
            constraints.setTargetCity(region.getCity());
            constraints.setTargetDistrict(region.getDistrict());
        }
        // Administrative scope and a POI may be stated together (for example,
        // "福州市福州大学").  Resolver validation must not erase the independent
        // targetArea extracted from the same turn.
        constraints.setLocationIntent("EXPLICIT_TARGET");
    }

    private void clearUnverifiedAdministrativeHint(DecisionConstraints constraints) {
        if (hasText(constraints.getTargetProvince())) constraints.getClearedFields().add("targetProvince");
        if (hasText(constraints.getTargetCity())) constraints.getClearedFields().add("targetCity");
        if (hasText(constraints.getTargetDistrict())) constraints.getClearedFields().add("targetDistrict");
        constraints.setTargetProvince("");
        constraints.setTargetCity("");
        constraints.setTargetDistrict("");
        constraints.setLocationIntent("UNSPECIFIED");
        if (!constraints.getMissingInformation().contains("administrativeRegion")) {
            constraints.getMissingInformation().add("administrativeRegion");
        }
    }

    private boolean hasAdministrativeHint(DecisionConstraints constraints) {
        return constraints != null && (hasText(constraints.getTargetProvince())
                || hasText(constraints.getTargetCity()) || hasText(constraints.getTargetDistrict()));
    }

    private AdministrativeRegionResolver resolver() {
        return administrativeRegionResolver == null ? new AdministrativeRegionResolver(objectMapper == null ? new ObjectMapper() : objectMapper) : administrativeRegionResolver;
    }

    /**
     * Nearby and explicit radius have deterministic semantics. Keep them stable
     * when a model omits one of these hard location fields.
     */
    private DecisionConstraints applySemanticLocationFallback(DecisionConstraints constraints, String query) {
        if (!Boolean.TRUE.equals(constraints.getNearby()) && containsAny(query, "附近", "周边", "就近")) {
            constraints.setNearby(true);
        }
        if (constraints.getRadiusKm() == null || constraints.getRadiusKm() <= 0) {
            Matcher radiusMatcher = RADIUS_PATTERN.matcher(query == null ? "" : query);
            if (radiusMatcher.find()) {
                double radiusValue = Double.parseDouble(radiusMatcher.group(1));
                String unit = radiusMatcher.group(2);
                constraints.setRadiusKm("米".equals(unit) || "m".equalsIgnoreCase(unit)
                        ? radiusValue / 1000D : radiusValue);
            }
        }
        return constraints;
    }

    /** Rule fallback for relative intent (critique) that runs on BOTH model and rule paths.
     *  Previously this lived only in extractByRule (LLM-failure path), so normal model extraction
     *  never saw "好贵/平价" — R1 bug: budgetDirection stayed 0 on an opening critique. */
    private DecisionConstraints applyDirectionFallback(DecisionConstraints constraints, String query) {
        if ((constraints.getBudgetDirection() == null || constraints.getBudgetDirection() == 0)
                && containsAny(query, "好贵", "太贵", "平价", "便宜", "实惠", "贵一点", "更便宜", "有点贵")) {
            constraints.setBudgetDirection(-1);
        }
        if ((constraints.getRadiusDirection() == null || constraints.getRadiusDirection() == 0)
                && containsAny(query, "更近", "近一点", "近点", "附近一点", "太远", "远一点")) {
            constraints.setRadiusDirection(-1);
        }
        return constraints;
    }

    private DecisionConstraints applyMutations(DecisionConstraints constraints, String query) {
        if (containsAny(query, "不限预算", "预算不限", "不限制预算")) constraints.getClearedFields().add("budgetPerPerson");
        if (containsAny(query, "不限距离", "不限定距离", "不考虑距离")) { constraints.getClearedFields().add("radiusKm"); constraints.getClearedFields().add("nearby"); }
        if (isLocationScopeRefusal(query)) { constraints.getClearedFields().add("radiusKm"); constraints.getClearedFields().add("nearby"); }
        if (containsAny(query, "不限定店名", "不指定店名")) constraints.getClearedFields().add("keyword");
        if (containsAny(query, "不用安静", "不用太安静", "不要安静", "不想安静")) constraints.getRemovedPreferences().add("安静");
        if (containsAny(query, "排队也行", "可以排队", "不介意排队", "排队没关系")) constraints.getRemovedPreferences().add("不排队");
        return constraints;
    }

    /** Structured negative cuisine semantics; the canonicalizer remains the single cuisine authority. */
    private void applyExcludedCuisine(DecisionConstraints constraints, String query) {
        Matcher matcher = EXCLUDED_CUISINE_PATTERN.matcher(query == null ? "" : query.replaceAll("\\s+", ""));
        if (!matcher.find()) return;
        String cuisine = CuisineCanonicalizer.canonicalize(matcher.group(1));
        if (cuisine.isBlank()) return;
        if (constraints.getExcludedCuisines() == null) constraints.setExcludedCuisines(new ArrayList<>());
        if (!constraints.getExcludedCuisines().contains(cuisine)) constraints.getExcludedCuisines().add(cuisine);
        constraints.setCuisine("");
        if (!constraints.getClearedFields().contains("cuisine")) constraints.getClearedFields().add("cuisine");
    }

    private boolean isLocationScopeRefusal(String query) {
        return containsAny(query, "不用管我的具体位置", "不提供位置", "按全城搜索", "全城搜索", "不看位置", "不需要定位");
    }

    private DecisionConstraints enforceCurrentDeviceIntent(DecisionConstraints constraints, String query) {
        if (!containsCurrentDeviceReference(query)) return constraints;
        constraints.setLocationIntent("CURRENT_DEVICE");
        constraints.setNearby(true);
        constraints.setTargetProvince("");
        constraints.setTargetCity("");
        constraints.setTargetDistrict("");
        constraints.setTargetArea("");
        constraints.getMissingInformation().remove("administrativeRegion");
        return constraints;
    }

    private DecisionConstraints extractByModel(String query) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", "你是餐饮消费决策需求解析器。只能根据用户原话提取约束；显式目标地点与设备当前位置必须分开：targetProvince 是用户明确要求搜索的省级行政范围，targetCity 是用户明确要求搜索的城市（上海/北京等直辖市也放这里），targetDistrict 是用户明确指定的区或县等行政范围，targetArea 是商圈、地标或 POI 等非行政地点，不能把它们放进 keyword。用户只说城市时不要凭常识补填其所属省份；只有用户同时明确说出省和城市时才同时填写。短句或承接句也要提取其中明确地点，例如‘那厦门呢’应填写 targetCity=厦门市；‘在闽侯内’应填写 targetDistrict=闽侯县；‘解放碑附近’应填写 targetArea=解放碑。城市、省份和区县使用规范行政名称。未知值使用空字符串、-1 或 false，不得臆测。"
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
        if (constraints.getTargetProvince() == null) constraints.setTargetProvince("");
        if (constraints.getTargetCity() == null) constraints.setTargetCity("");
        if (constraints.getTargetDistrict() == null) constraints.setTargetDistrict("");
        if (constraints.getTargetArea() == null) constraints.setTargetArea("");
        constraints.setLocationIntent(normalizeLocationIntent(constraints.getLocationIntent(), constraints));
        if (constraints.getKeyword() == null) constraints.setKeyword("");
        if (constraints.getExcludedCuisines() == null) constraints.setExcludedCuisines(new ArrayList<>());
        migrateCuisineKeywordToCuisine(constraints);
        constraints.setCuisine(canonicalizeCuisine(constraints.getCuisine()));
        if (constraints.getBudgetPerPerson() == null) constraints.setBudgetPerPerson(-1);
        if (constraints.getBudgetDirection() == null) constraints.setBudgetDirection(0);
        if (constraints.getRadiusKm() == null) constraints.setRadiusKm(-1D);
        if (constraints.getRadiusDirection() == null) constraints.setRadiusDirection(0);
        if (constraints.getNearby() == null) constraints.setNearby(false);
        if (constraints.getArrivalTime() == null) constraints.setArrivalTime("");
        constraints.setPreferences(normalizePreferences(constraints.getPreferences()));
        if (constraints.getSystemNotes() == null) constraints.setSystemNotes(new ArrayList<String>());
        if (constraints.getMissingInformation() == null) constraints.setMissingInformation(new ArrayList<String>());
        if (constraints.getLockedConstraints() == null) constraints.setLockedConstraints(new java.util.HashSet<String>());
        if (constraints.getClearedFields() == null) constraints.setClearedFields(new ArrayList<String>());
        if (constraints.getRemovedPreferences() == null) constraints.setRemovedPreferences(new ArrayList<String>());
        return constraints;
    }

    private String canonicalizeCuisine(String cuisine) {
        return CuisineCanonicalizer.canonicalize(cuisine);
    }

    /**
     * If the user explicitly names a cuisine keyword (e.g. "沙县" -> canonical "小吃") but the
     * extractor put it in keyword, migrate it to cuisine so it participates in deterministic
     * hard filtering instead of remaining a semantic-only signal. Only migrates when cuisine is
     * empty to avoid overriding an already-extracted cuisine.
     */
    private void migrateCuisineKeywordToCuisine(DecisionConstraints constraints) {
        String keyword = constraints.getKeyword();
        if (keyword == null || keyword.trim().isEmpty()) return;
        if (constraints.getCuisine() != null && !constraints.getCuisine().isEmpty()) return;
        String trimmed = keyword.trim();
        String canonical = CuisineCanonicalizer.canonicalize(trimmed);
        if (!canonical.equals(trimmed)) { // 命中菜系映射表（如 沙县->小吃）
            constraints.setCuisine(canonical);
            constraints.setKeyword("");
        }
    }

    /** Normalizes open-set preferences into canonical tags where a rule map consumes them; leaves open-ended phrases as-is. */
    private List<String> normalizePreferences(List<String> preferences) {
        return PreferenceCanonicalizer.canonicalizeAll(preferences);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String normalizeLocationIntent(String locationIntent, DecisionConstraints constraints) {
        if ("CURRENT_DEVICE".equalsIgnoreCase(locationIntent)) return "CURRENT_DEVICE";
        if ("EXPLICIT_TARGET".equalsIgnoreCase(locationIntent)
                || hasText(constraints.getTargetProvince()) || hasText(constraints.getTargetCity()) || hasText(constraints.getTargetDistrict()) || hasText(constraints.getTargetArea())) return "EXPLICIT_TARGET";
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
        properties.put("targetProvince", property("string", "Explicit target province requested by the user, for example 福建省 or 广东省. Empty string if absent. Do not infer a parent province when the user only names a city."));
        properties.put("targetCity", property("string", "Explicit target city requested by the user, for example 重庆 or 福州. Empty string if absent."));
        properties.put("targetDistrict", property("string", "Explicit administrative district or county requested by the user, for example 闽侯县 or 鼓楼区. Empty string if absent. Do not put landmarks or business areas here."));
        properties.put("targetArea", property("string", "Explicit business area or landmark/POI, for example 解放碑 or 福州大学. Empty string if absent; do not put administrative districts here."));
        properties.put("locationIntent", property("string", "EXPLICIT_TARGET for a named destination, CURRENT_DEVICE for the user's current location, or UNSPECIFIED."));
        properties.put("keyword", property("string", "Specific entity the user explicitly names: a restaurant name or signature dish (e.g. 闽师东北菜, 锅包肉). Do NOT put a cuisine here — cuisines go to the cuisine field. Do not include targetCity or targetArea."));
        properties.put("cuisine", property("string", "Cuisine category (e.g. 川菜, 火锅, 日料, 快餐简餐, 面食, 小吃). Empty string if unknown."));
        properties.put("excludedCuisines", arrayProperty("Cuisine categories the user explicitly excludes, for example 东北菜 in ‘除了东北菜都可以’. Empty array if none."));
        properties.put("budgetPerPerson", property("integer", "Maximum per-person budget. -1 if unknown."));
        properties.put("budgetDirection", property("integer", "Relative budget intent WITHOUT an absolute number: -1 = cheaper (太贵/好贵/平价一点/便宜点/实惠), 0 = no relative intent, 1 = more expensive. Only set on a relative price critique; leave 0 otherwise."));
        properties.put("radiusKm", property("number", "Search radius in kilometers. -1 if unknown."));
        properties.put("radiusDirection", property("integer", "Relative distance intent: -1 = closer (更近/近一点/太远), 0 = no relative intent, 1 = farther. Only set on a relative distance critique; leave 0 otherwise."));
        properties.put("nearby", property("boolean", "Whether the user uses a nearby/local intent."));
        properties.put("arrivalTime", property("string", "Arrival time HH:mm. Empty string if unknown."));
        properties.put("preferences", arrayProperty("Open-set soft preferences as natural-language tags, e.g. 安静, 不排队, 约会, 便餐, 清淡, 辣, 适合聚餐, 氛围好, 性价比高. This is an open set — do not restrict to an enum. Do NOT include constraints the user explicitly cancelled or negated."));
        properties.put("missingInformation", arrayProperty("Information needed but not supplied."));
        properties.put("clearedFields", arrayProperty("Constraint fields the user explicitly abandons: cuisine, keyword, budgetPerPerson, radiusKm, nearby, targetProvince, targetCity, targetDistrict, targetArea, arrivalTime, preferences. Empty array if none."));
        properties.put("removedPreferences", arrayProperty("Explicitly removed preference tags, for example 安静 when user says 不用安静了."));
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
