package com.hmdp.ai.service;

import com.hmdp.ai.dto.CriteriaMergeResult;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.mapper.AiShopProfileMapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Applies an explicitly expressed constraint delta without asking the model to infer absent fields. */
@Service
public class ConversationCriteriaMerger {
    private static final Logger log = LoggerFactory.getLogger(ConversationCriteriaMerger.class);

    @Resource
    private AiShopProfileMapper shopProfileMapper;

    public CriteriaMergeResult merge(DecisionConstraints previous, DecisionConstraints delta, String query) {
        return merge(previous, delta, query, new ArrayList<DecisionRecommendation>(), null, new ArrayList<Long>());
    }

    /** Reduces an explicit delta using the previous recommendation only for relative constraints. */
    public CriteriaMergeResult merge(DecisionConstraints previous, DecisionConstraints delta, String query,
                                     List<DecisionRecommendation> candidatePool, Long focusedShopId, List<Long> shownShopIds) {
        DecisionConstraints merged = copy(previous);
        CriteriaMergeResult result = new CriteriaMergeResult();
        result.setConstraints(merged);
        String text = query == null ? "" : query.replaceAll("\\s+", "");

        inherit(result, previous);
        applyClearedFields(result, merged, delta, text);
        if (isDemandReplacement(text, previous, delta)) {
            clear(result, "budgetPerPerson", () -> merged.setBudgetPerPerson(-1));
            clear(result, "preferences", () -> merged.setPreferences(new ArrayList<String>()));
            clear(result, "lockedConstraints", () -> merged.setLockedConstraints(new java.util.HashSet<String>()));
            clear(result, "keyword", () -> merged.setKeyword(""));
        }
        if (isCurrentLocationIntent(text)) {
            clear(result, "targetCity", () -> merged.setTargetCity(""));
            clear(result, "targetArea", () -> merged.setTargetArea(""));
            replace(result, "locationIntent", merged.getLocationIntent(), "CURRENT_DEVICE",
                    () -> merged.setLocationIntent("CURRENT_DEVICE"));
        } else {
            if (hasText(delta.getTargetCity())) replace(result, "targetCity", merged.getTargetCity(), delta.getTargetCity(), () -> merged.setTargetCity(delta.getTargetCity()));
            if (hasText(delta.getTargetArea())) replace(result, "targetArea", merged.getTargetArea(), delta.getTargetArea(), () -> merged.setTargetArea(delta.getTargetArea()));
            if (hasText(delta.getLocationIntent()) && !"UNSPECIFIED".equals(delta.getLocationIntent())) {
                replace(result, "locationIntent", merged.getLocationIntent(), delta.getLocationIntent(),
                        () -> merged.setLocationIntent(delta.getLocationIntent()));
            }
        }
        if (hasText(delta.getKeyword())) replace(result, "keyword", merged.getKeyword(), delta.getKeyword(), () -> merged.setKeyword(delta.getKeyword()));
        if (hasText(delta.getCuisine())) replace(result, "cuisine", merged.getCuisine(), delta.getCuisine(), () -> merged.setCuisine(delta.getCuisine()));
        if (delta.getBudgetPerPerson() != null && delta.getBudgetPerPerson() > 0) {
            replace(result, "budgetPerPerson", String.valueOf(merged.getBudgetPerPerson()), String.valueOf(delta.getBudgetPerPerson()),
                    () -> merged.setBudgetPerPerson(delta.getBudgetPerPerson()));
            unlockBudgetWhenExplicitlyOverridden(result, merged);
        }
        if (delta.getRadiusKm() != null && delta.getRadiusKm() > 0) replace(result, "radiusKm", String.valueOf(merged.getRadiusKm()), String.valueOf(delta.getRadiusKm()), () -> merged.setRadiusKm(delta.getRadiusKm()));
        if (hasText(delta.getArrivalTime())) replace(result, "arrivalTime", merged.getArrivalTime(), delta.getArrivalTime(), () -> merged.setArrivalTime(delta.getArrivalTime()));
        if (Boolean.TRUE.equals(delta.getNearby())) replace(result, "nearby", String.valueOf(merged.getNearby()), "true", () -> merged.setNearby(true));
        // preferences: append explicit new tags (dedup); explicit negation removes the tag.
        append(result, "preferences", merged.getPreferences(), delta.getPreferences());
        if (containsAny(text, "不要安静", "不用安静", "不用太安静", "别太安静")) removePreference(result, merged, "安静");
        if (containsAny(text, "排队也行", "不用避开排队", "不想避开排队")) removePreference(result, merged, "不排队");
        if (containsAny(text, "不要辣", "不吃辣", "清淡", "少油", "不油腻")) addPreference(result, merged, "清淡");

        if (containsAny(text, "不限菜系", "什么都行", "随便吃", "不限制菜系")) clear(result, "cuisine", () -> merged.setCuisine(""));
        if (containsAny(text, "预算不限", "不限制预算", "人均不限")) {
            clear(result, "budgetPerPerson", () -> merged.setBudgetPerPerson(-1));
            unlockBudgetWhenExplicitlyOverridden(result, merged);
        }
        if (containsAny(text, "不限距离", "不考虑距离", "全城都行")) {
            clear(result, "radiusKm", () -> merged.setRadiusKm(-1D));
            clear(result, "nearby", () -> merged.setNearby(false));
        }
        applyRelativeConstraints(result, merged, delta, text, candidatePool, focusedShopId, shownShopIds);

        merged.setPreferences(unique(merged.getPreferences()));
        return result;
    }

    private void applyRelativeConstraints(CriteriaMergeResult result, DecisionConstraints merged, DecisionConstraints delta,
                                          String text, List<DecisionRecommendation> candidatePool, Long focusedShopId, List<Long> shownShopIds) {
        boolean cheaper = containsAny(text, "太贵", "便宜点", "更便宜", "好贵", "平价", "实惠", "有点贵", "贵一点")
                || (delta.getBudgetDirection() != null && delta.getBudgetDirection() < 0);
        if (cheaper) {
            Long anchorPrice = relativePriceAnchor(candidatePool, focusedShopId, shownShopIds);
            if (anchorPrice == null) {
                // opening critique (R1): no pool/shown/focused anchor yet — fall back to a cuisine-level
                // default price band so "平价一点" still lands a hard budget instead of no-op.
                // P25 data-driven percentile is the second-batch evolution (待修 #50).
                anchorPrice = defaultBudgetAnchor(merged.getCuisine());
            }
            if (anchorPrice != null && anchorPrice > 1L) {
                // proportional step instead of anchorPrice-1: a -1 unit is meaningless for price (GLM review 2026-09-04)
                int budget = Math.max(20, (int) Math.round(anchorPrice * 0.85D));
                replace(result, "budgetPerPerson", String.valueOf(merged.getBudgetPerPerson()), String.valueOf(budget),
                        () -> merged.setBudgetPerPerson(budget));
                if (merged.getLockedConstraints() == null) merged.setLockedConstraints(new java.util.HashSet<String>());
                if (merged.getLockedConstraints().add("budgetPerPerson")) {
                    result.getAppended().add("lockedConstraints:budgetPerPerson");
                }
                result.getAppended().add("relativeBudget:anchorPrice=" + anchorPrice + "->budgetPerPerson=" + budget);
            }
        }
        boolean closer = containsAny(text, "更近", "近一点", "近点", "附近一点", "太远", "远一点")
                || (delta.getRadiusDirection() != null && delta.getRadiusDirection() < 0);
        if (closer) {
            Double anchorDistance = relativeDistanceAnchor(candidatePool, focusedShopId, shownShopIds);
            if (anchorDistance != null && anchorDistance > 0.5D) {
                double radius = Math.max(0.5D, Math.round(anchorDistance * 0.9D * 10D) / 10D);
                replace(result, "radiusKm", String.valueOf(merged.getRadiusKm()), String.valueOf(radius),
                        () -> merged.setRadiusKm(radius));
                result.getAppended().add("relativeDistance:anchorKm=" + anchorDistance + "->radiusKm=" + radius);
            }
        }
        // one-shot pulse: consume-and-reset so a residual direction is never re-applied on a later turn
        if (delta != null) {
            delta.setBudgetDirection(0);
            delta.setRadiusDirection(0);
        }
        merged.setBudgetDirection(0);
        merged.setRadiusDirection(0);
    }

    /** Default price band per cuisine for opening critiques (no candidate pool yet).
     *  Hard-coded minimal version; second batch replaces with cuisine price-distribution P25 (待修 #50). */
    private Long defaultBudgetAnchor(String cuisine) {
        // Layer 6 data-driven anchor: AVG(price) * 0.8 before hardcoded fallback.
        // Hardcoded bands were severely mis-calibrated (火锅=60 vs actual AVG=153), so DB lookup is primary.
        if (cuisine != null && !cuisine.isEmpty()) {
            try {
                Integer avg = shopProfileMapper.selectAvgPriceByCuisine(cuisine);
                if (avg != null && avg > 0) {
                    return Math.round(avg * 0.8D);
                }
            } catch (Exception e) {
                log.warn("[AI][critique] defaultAnchor db lookup failed for cuisine={}, fallback hardcoded", cuisine, e);
            }
        }
        if (cuisine == null || cuisine.isEmpty()) return 60L;
        switch (cuisine) {
            case "火锅": return 60L;
            case "烧烤": return 70L;
            case "日料": return 85L;
            case "韩餐": return 70L;
            case "西餐": return 90L;
            case "东南亚": return 70L;
            case "港式": return 65L;
            case "快餐简餐": return 30L;
            case "面食": return 25L;
            case "粉面": return 25L;
            case "饺子馄饨": return 30L;
            case "小吃": return 25L;
            case "自助餐": return 80L;
            case "海鲜": return 100L;
            case "素食": return 45L;
            case "咖啡": return 40L;
            case "甜品饮品": return 35L;
            case "面包烘焙": return 30L;
            default: return 60L;
        }
    }

    /** Anchor = the shop the user is actually complaining about: focused shop first, then the shown (displayed) set,
     *  then the pool. A user can only complain about shops they have seen (GLM review 2026-09-04). */
    private Long relativePriceAnchor(List<DecisionRecommendation> candidates, Long focusedShopId, List<Long> shownShopIds) {
        if (candidates == null || candidates.isEmpty()) return null;
        if (focusedShopId != null) {
            for (DecisionRecommendation candidate : candidates) {
                if (focusedShopId.equals(candidate.getShopId()) && candidate.getAvgPrice() != null) {
                    return candidate.getAvgPrice();
                }
            }
        }
        if (shownShopIds != null && !shownShopIds.isEmpty()) {
            Long shownMin = null;
            for (DecisionRecommendation candidate : candidates) {
                if (candidate.getShopId() == null || !shownShopIds.contains(candidate.getShopId())) continue;
                if (candidate.getAvgPrice() == null || candidate.getAvgPrice() <= 0L) continue;
                if (shownMin == null || candidate.getAvgPrice() < shownMin) shownMin = candidate.getAvgPrice();
            }
            if (shownMin != null) return shownMin;
        }
        Long minimum = null;
        for (DecisionRecommendation candidate : candidates) {
            if (candidate.getAvgPrice() == null || candidate.getAvgPrice() <= 0L) continue;
            if (minimum == null || candidate.getAvgPrice() < minimum) minimum = candidate.getAvgPrice();
        }
        return minimum;
    }

    private Double relativeDistanceAnchor(List<DecisionRecommendation> candidates, Long focusedShopId, List<Long> shownShopIds) {
        if (candidates == null || candidates.isEmpty()) return null;
        if (focusedShopId != null) {
            for (DecisionRecommendation candidate : candidates) {
                if (focusedShopId.equals(candidate.getShopId()) && candidate.getDistanceKm() != null) {
                    return candidate.getDistanceKm();
                }
            }
        }
        if (shownShopIds != null && !shownShopIds.isEmpty()) {
            Double shownMin = null;
            for (DecisionRecommendation candidate : candidates) {
                if (candidate.getShopId() == null || !shownShopIds.contains(candidate.getShopId())) continue;
                if (candidate.getDistanceKm() == null || candidate.getDistanceKm() <= 0D) continue;
                if (shownMin == null || candidate.getDistanceKm() < shownMin) shownMin = candidate.getDistanceKm();
            }
            if (shownMin != null) return shownMin;
        }
        Double minimum = null;
        for (DecisionRecommendation candidate : candidates) {
            if (candidate.getDistanceKm() == null || candidate.getDistanceKm() <= 0D) continue;
            if (minimum == null || candidate.getDistanceKm() < minimum) minimum = candidate.getDistanceKm();
        }
        return minimum;
    }

    private DecisionConstraints copy(DecisionConstraints source) {
        DecisionConstraints target = new DecisionConstraints();
        if (source == null) return target;
        target.setTargetCity(source.getTargetCity()); target.setTargetArea(source.getTargetArea()); target.setKeyword(source.getKeyword()); target.setLocationIntent(source.getLocationIntent());
        target.setCuisine(source.getCuisine()); target.setBudgetPerPerson(source.getBudgetPerPerson());
        target.setRadiusKm(source.getRadiusKm()); target.setNearby(source.getNearby());
        target.setArrivalTime(source.getArrivalTime());
        target.setPreferences(new ArrayList<String>(source.getPreferences() == null ? new ArrayList<String>() : source.getPreferences()));
        target.setSystemNotes(new ArrayList<String>(source.getSystemNotes() == null ? new ArrayList<String>() : source.getSystemNotes()));
        target.setMissingInformation(new ArrayList<String>(source.getMissingInformation() == null ? new ArrayList<String>() : source.getMissingInformation()));
        target.setLockedConstraints(new java.util.HashSet<String>(source.getLockedConstraints() == null ? new java.util.HashSet<String>() : source.getLockedConstraints()));
        return target;
    }

    /** Executes the LLM-extracted cleared fields (semantic judgment moved out of rule text matching),
     *  plus a conservative rule fallback for the reproduced "换别的品类" blind spot. */
    private void applyClearedFields(CriteriaMergeResult result, DecisionConstraints merged,
                                    DecisionConstraints delta, String text) {
        java.util.List<String> cleared = delta == null ? null : delta.getClearedFields();
        if (cleared != null) {
            for (String field : cleared) {
                if (field == null) continue;
                if ("cuisine".equals(field) && hasText(merged.getCuisine())) {
                    clear(result, "cuisine", () -> merged.setCuisine(""));
                } else if ("keyword".equals(field) && hasText(merged.getKeyword())) {
                    clear(result, "keyword", () -> merged.setKeyword(""));
                } else if ("budgetPerPerson".equals(field) && merged.getBudgetPerPerson() != null && merged.getBudgetPerPerson() > 0) {
                    clear(result, "budgetPerPerson", () -> merged.setBudgetPerPerson(-1));
                } else if ("radiusKm".equals(field) && merged.getRadiusKm() != null && merged.getRadiusKm() > 0) {
                    clear(result, "radiusKm", () -> merged.setRadiusKm(-1D));
                } else if ("nearby".equals(field) && Boolean.TRUE.equals(merged.getNearby())) {
                    clear(result, "nearby", () -> merged.setNearby(false));
                } else if ("targetCity".equals(field) && hasText(merged.getTargetCity())) {
                    clear(result, "targetCity", () -> merged.setTargetCity(""));
                } else if ("targetArea".equals(field) && hasText(merged.getTargetArea())) {
                    clear(result, "targetArea", () -> merged.setTargetArea(""));
                } else if ("preferences".equals(field) && merged.getPreferences() != null && !merged.getPreferences().isEmpty()) {
                    clear(result, "preferences", () -> merged.setPreferences(new ArrayList<String>()));
                } else if ("arrivalTime".equals(field) && hasText(merged.getArrivalTime())) {
                    clear(result, "arrivalTime", () -> merged.setArrivalTime(""));
                }
            }
        }
        if (containsAny(text, "看看有没有别的吃的", "看看有没有别的", "换个别的", "换种", "别的餐厅", "别的店", "别的吃的", "别的菜品")) {
            if (hasText(merged.getCuisine())) clear(result, "cuisine", () -> merged.setCuisine(""));
            if (hasText(merged.getKeyword())) clear(result, "keyword", () -> merged.setKeyword(""));
        }
    }

    private void inherit(CriteriaMergeResult result, DecisionConstraints previous) {
        if (previous == null) return;
        if (hasText(previous.getTargetCity())) result.getInherited().add("targetCity=" + previous.getTargetCity());
        if (hasText(previous.getTargetArea())) result.getInherited().add("targetArea=" + previous.getTargetArea());
        if (hasText(previous.getLocationIntent())) result.getInherited().add("locationIntent=" + previous.getLocationIntent());
        if (hasText(previous.getKeyword())) result.getInherited().add("keyword=" + previous.getKeyword());
        if (hasText(previous.getCuisine())) result.getInherited().add("cuisine=" + previous.getCuisine());
        if (previous.getBudgetPerPerson() != null && previous.getBudgetPerPerson() > 0) result.getInherited().add("budgetPerPerson=" + previous.getBudgetPerPerson());
        if (previous.getRadiusKm() != null && previous.getRadiusKm() > 0) result.getInherited().add("radiusKm=" + previous.getRadiusKm());
        if (previous.getPreferences() != null && !previous.getPreferences().isEmpty()) result.getInherited().add("preferences=" + previous.getPreferences());
    }

    private void replace(CriteriaMergeResult result, String field, String before, String after, Runnable mutation) {
        mutation.run();
        if (!String.valueOf(before).equals(String.valueOf(after))) result.getReplaced().add(field + ":" + before + "->" + after);
    }

    private void clear(CriteriaMergeResult result, String field, Runnable mutation) {
        mutation.run();
        if (!result.getCleared().contains(field)) result.getCleared().add(field);
    }

    private void append(CriteriaMergeResult result, String field, List<String> target, List<String> additions) {
        if (additions == null || additions.isEmpty()) return;
        for (String item : additions) {
            if (!hasText(item) || target.contains(item)) continue;
            target.add(item);
            result.getAppended().add(field + ":" + item);
        }
    }

    private void addPreference(CriteriaMergeResult result, DecisionConstraints constraints, String preference) {
        if (constraints.getPreferences() == null) constraints.setPreferences(new ArrayList<String>());
        if (!constraints.getPreferences().contains(preference)) {
            constraints.getPreferences().add(preference);
            result.getAppended().add("preferences:" + preference);
        }
    }

    private void removePreference(CriteriaMergeResult result, DecisionConstraints constraints, String preference) {
        if (constraints.getPreferences() == null) return;
        if (constraints.getPreferences().remove(preference)) {
            result.getCleared().add("preferences:" + preference);
        }
    }

    private void unlockBudgetWhenExplicitlyOverridden(CriteriaMergeResult result, DecisionConstraints constraints) {
        if (constraints.getLockedConstraints() != null && constraints.getLockedConstraints().remove("budgetPerPerson")) {
            result.getCleared().add("lockedConstraints:budgetPerPerson");
        }
    }

    private List<String> unique(List<String> values) { return new ArrayList<String>(new LinkedHashSet<String>(values == null ? new ArrayList<String>() : values)); }
    private boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }
    private boolean isCurrentLocationIntent(String text) { return containsAny(text, "我附近", "我这附近", "当前位置", "当前定位", "我这里", "我身边"); }
    /** “改成/换成 + 新品类” replaces the old demand instead of silently retaining its hard filters. */
    private boolean isDemandReplacement(String text, DecisionConstraints previous, DecisionConstraints delta) {
        if (previous == null || delta == null) return false;
        boolean cuisineChanged = hasText(delta.getCuisine()) && !delta.getCuisine().equals(previous.getCuisine());
        boolean keywordChanged = hasText(delta.getKeyword()) && !delta.getKeyword().equals(previous.getKeyword());
        if (!cuisineChanged && !keywordChanged) return false;
        return containsAny(text, "算了", "不吃了", "重新来", "换个需求");
    }
    private boolean containsAny(String source, String... values) { for (String value : values) if (source.contains(value)) return true; return false; }
}
