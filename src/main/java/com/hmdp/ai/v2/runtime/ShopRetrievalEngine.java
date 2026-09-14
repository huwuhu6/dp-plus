package com.hmdp.ai.v2.runtime;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.dto.SemanticRecallResult;
import com.hmdp.ai.entity.AiReviewDocument;
import com.hmdp.ai.entity.AiShopProfile;
import com.hmdp.ai.mapper.AiReviewDocumentMapper;
import com.hmdp.ai.mapper.AiShopProfileMapper;
import com.hmdp.ai.service.SemanticShopRetriever;
import com.hmdp.ai.util.CuisineCanonicalizer;
import com.hmdp.ai.v2.plan.ExecutionAction;
import com.hmdp.ai.v2.plan.SearchSpec;
import com.hmdp.ai.v2.semantic.DiningCriteria;
import com.hmdp.ai.v2.semantic.RequirementChange;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Read-only retrieval boundary shared by the decision shell and V2. Hard-filtered MySQL
 * candidates form the Milvus whitelist; semantic scores and deterministic evidence rerank
 * only that whitelist. No session, message, or WorkingMemory lifecycle lives here.
 */
@Service
public class ShopRetrievalEngine {
    @Resource private ShopMapper shopMapper;
    @Resource private AiShopProfileMapper profileMapper;
    @Resource private AiReviewDocumentMapper reviewMapper;
    @Autowired(required = false) private SemanticShopRetriever semanticShopRetriever;
    @Value("${ai.retrieval.semantic-weight:18}") private double semanticWeight;

    public RetrievalResult retrieve(SearchSpec spec) {
        Long similarId = spec.anchor() == null ? null : spec.anchor().shopId();
        return retrieve(new ShopRetrievalRequest(spec.criteria(), spec.relativePreferences(), spec.kind(),
                spec.excludedShopIds(), similarId, spec.searchAnchor(), spec.count(), null));
    }

    public RetrievalResult retrieve(ShopRetrievalRequest request) {
        if (request.kind() == ExecutionAction.SearchKind.SIMILAR && request.similarityAnchorShopId() == null)
            return RetrievalResult.unsupported("相似推荐需要明确的参考商户。");
        DiningCriteria criteria = request.criteria();
        if (requiresDistance(criteria) && !hasCoordinates(request.searchAnchor()))
            return RetrievalResult.unsupported("当前距离条件缺少可用定位，请提供当前位置或明确地点。");

        long started = System.currentTimeMillis();
        QueryWrapper<Shop> query = new QueryWrapper<>();
        applyStructuredSqlFilters(query, criteria, request);
        List<Shop> initial = shopMapper.selectList(query);
        if (initial.isEmpty()) return RetrievalResult.success(List.of(), RetrievalMetrics.empty(System.currentTimeMillis() - started));
        Set<Long> candidateIds = initial.stream().map(Shop::getId).collect(Collectors.toSet());
        Map<Long, AiShopProfile> profiles = loadProfiles(candidateIds);
        Map<Long, List<AiReviewDocument>> reviews = loadReviews(candidateIds);
        List<Shop> hardMatched = initial.stream().filter(shop -> hardMatch(shop, profiles.get(shop.getId()), reviews.get(shop.getId()), criteria, request.searchAnchor())).toList();
        if (hardMatched.isEmpty()) return RetrievalResult.success(List.of(), new RetrievalMetrics(initial.size(), 0, 0, false,
                System.currentTimeMillis() - started, 0));

        String semanticQuery = semanticQuery(request, profiles);
        SemanticRecallResult recall = semanticShopRetriever == null || semanticQuery.isBlank()
                ? SemanticRecallResult.unavailable()
                : semanticShopRetriever.recall(semanticQuery, hardMatched, profiles, reviews);
        if (request.kind() == ExecutionAction.SearchKind.SIMILAR && (recall == null || !recall.isAvailable()))
            return RetrievalResult.unsupported("当前无法使用商户语义索引完成相似推荐。");
        Map<Long, Double> semanticScores = recall == null ? Map.of() : recall.getShopScores();

        List<DecisionRecommendation> recommendations = new ArrayList<>();
        for (Shop shop : hardMatched) {
            if (request.kind() == ExecutionAction.SearchKind.SIMILAR && shop.getId().equals(request.similarityAnchorShopId())) continue;
            if (request.kind() == ExecutionAction.SearchKind.SIMILAR && !semanticScores.containsKey(shop.getId())) continue;
            DecisionRecommendation recommendation = toRecommendation(shop, profiles.get(shop.getId()), reviews.get(shop.getId()), criteria, request.searchAnchor());
            Double semanticScore = semanticScores.get(shop.getId());
            if (semanticScore != null) {
                recommendation.setSemanticScore(round(semanticScore));
                recommendation.setScore(round(Math.min(100D, recommendation.getScore() + semanticScore * semanticWeight)));
                recommendation.getMatchedReasons().add(request.kind() == ExecutionAction.SearchKind.SIMILAR ? "与参考商户的语义特征相近" : "语义证据与当前需求相关");
            }
            applyRelativeRanking(recommendation, shop, request.relativePreferences());
            recommendations.add(recommendation);
        }
        recommendations.sort(Comparator.comparing(DecisionRecommendation::getScore, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(DecisionRecommendation::getShopId));
        int limit = request.count() <= 0 ? 3 : Math.min(request.count(), 10);
        List<DecisionRecommendation> selected = recommendations.stream().limit(limit).toList();
        long duration = System.currentTimeMillis() - started;
        return RetrievalResult.success(selected, new RetrievalMetrics(initial.size(), hardMatched.size(), selected.size(),
                recall != null && recall.isAvailable(), duration, recall == null ? 0 : recall.getDurationMs()));
    }

    private void applyStructuredSqlFilters(QueryWrapper<Shop> query, DiningCriteria criteria, ShopRetrievalRequest request) {
        DiningCriteria.LocationCriteria location = criteria.location();
        String city = location == null ? null : location.city();
        String district = location == null ? null : location.district();
        if ((city == null || city.isBlank()) && request.searchAnchor() != null) city = request.searchAnchor().city();
        if ((district == null || district.isBlank()) && request.searchAnchor() != null) district = request.searchAnchor().district();
        String province = request.searchAnchor() == null ? null : request.searchAnchor().province();
        if (province != null && !province.isBlank()) query.eq("province", province);
        if (city != null && !city.isBlank()) query.in("city", city, city.endsWith("市") ? city.substring(0, city.length() - 1) : city + "市");
        if (district != null && !district.isBlank()) query.eq("district", district);
        if (criteria.budget() != null && criteria.budget().hardMax() != null) query.le("avg_price", criteria.budget().hardMax().longValue());
        if (!request.excludedShopIds().isEmpty()) query.notIn("id", request.excludedShopIds());
    }

    private Map<Long, AiShopProfile> loadProfiles(Set<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return profileMapper.selectList(new QueryWrapper<AiShopProfile>().in("shop_id", ids)).stream()
                .collect(Collectors.toMap(AiShopProfile::getShopId, p -> p, (left, right) -> left));
    }
    private Map<Long, List<AiReviewDocument>> loadReviews(Set<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return reviewMapper.selectList(new QueryWrapper<AiReviewDocument>().in("shop_id", ids)).stream()
                .collect(Collectors.groupingBy(AiReviewDocument::getShopId));
    }

    private boolean hardMatch(Shop shop, AiShopProfile profile, List<AiReviewDocument> reviews,
                              DiningCriteria criteria, com.hmdp.ai.v2.plan.SearchAnchor anchor) {
        if (criteria.cuisine() != null && criteria.cuisine().include() != null && !criteria.cuisine().include().isBlank()
                && !matchesCuisine(profile, criteria.cuisine().include())) return false;
        if (criteria.cuisine() != null) for (String excluded : criteria.cuisine().exclude()) if (matchesCuisine(profile, excluded)) return false;
        if (criteria.distance() != null && criteria.distance().hardMaxKm() != null
                && distance(anchor.latitude(), anchor.longitude(), shop.getY(), shop.getX()) > criteria.distance().hardMaxKm().doubleValue()) return false;
        if (criteria.preferences() != null) for (DiningCriteria.SemanticPreference preference : criteria.preferences().values())
            if (preference.importance() == DiningCriteria.Importance.MUST && !hasRequiredEvidence(preference.criterion(), profile, reviews)) return false;
        return isOpenAt(shop.getOpenHours(), criteria.diningTime() == null ? null : criteria.diningTime().arrivalTime());
    }
    private boolean hasRequiredEvidence(String criterion, AiShopProfile profile, List<AiReviewDocument> reviews) {
        if (criterion == null || criterion.isBlank()) return false;
        if (profile != null && (contains(profile.getSceneTags(), criterion) || contains(profile.getAmbienceTags(), criterion)
                || contains(profile.getSummary(), criterion))) return true;
        List<String> evidenceTerms = "清淡".equals(criterion) ? List.of("清淡", "不油腻", "清爽", "少油") : List.of(criterion);
        return reviews != null && reviews.stream().map(AiReviewDocument::getContent).filter(Objects::nonNull)
                .anyMatch(content -> evidenceTerms.stream().anyMatch(content::contains));
    }
    private boolean matchesCuisine(AiShopProfile profile, String expected) {
        if (profile == null || profile.getCuisine() == null) return false;
        String canonical = CuisineCanonicalizer.canonicalize(expected);
        return Arrays.stream(profile.getCuisine().split(",")).map(CuisineCanonicalizer::canonicalize).anyMatch(canonical::equals);
    }
    private String semanticQuery(ShopRetrievalRequest request, Map<Long, AiShopProfile> profiles) {
        if (request.kind() == ExecutionAction.SearchKind.SIMILAR) {
            AiShopProfile anchor = profiles.get(request.similarityAnchorShopId());
            if (anchor == null) {
                List<AiShopProfile> loaded = profileMapper.selectList(new QueryWrapper<AiShopProfile>().eq("shop_id", request.similarityAnchorShopId()));
                anchor = loaded.isEmpty() ? null : loaded.getFirst();
            }
            if (anchor == null) return "";
            return String.join(" ", nonBlank(anchor.getCuisine(), anchor.getSceneTags(), anchor.getAmbienceTags(), anchor.getSummary()));
        }
        if (request.semanticQuery() != null && !request.semanticQuery().isBlank()) return request.semanticQuery();
        List<String> terms = new ArrayList<>(); DiningCriteria c = request.criteria();
        if (c.cuisine() != null) { if (c.cuisine().include() != null) terms.add(c.cuisine().include()); terms.addAll(c.cuisine().exclude()); }
        if (c.preferences() != null) c.preferences().values().forEach(p -> terms.add(p.criterion()));
        if (c.diningTime() != null && c.diningTime().arrivalTime() != null) terms.add(c.diningTime().arrivalTime());
        return String.join(" ", terms).trim();
    }
    private List<String> nonBlank(String... values) { return Arrays.stream(values).filter(v -> v != null && !v.isBlank()).toList(); }

    private DecisionRecommendation toRecommendation(Shop shop, AiShopProfile profile, List<AiReviewDocument> reviews,
                                                     DiningCriteria criteria, com.hmdp.ai.v2.plan.SearchAnchor anchor) {
        DecisionRecommendation item = new DecisionRecommendation(); item.setShopId(shop.getId()); item.setShopName(shop.getName());
        item.setAvgPrice(shop.getAvgPrice()); item.setAddress(shop.getAddress()); item.setOpenHours(shop.getOpenHours());
        if (profile != null) {
            item.setCuisine(profile.getCuisine());
            addTags(item, profile.getCuisine()); addTags(item, profile.getSceneTags()); addTags(item, profile.getAmbienceTags());
        }
        double score = shop.getScore() == null ? 0D : shop.getScore() / 10D / 5D * 20D;
        if (criteria.budget() != null && criteria.budget().softTarget() != null && shop.getAvgPrice() != null) {
            double target = criteria.budget().softTarget().doubleValue(); score += 20D * (1D - ((double) shop.getAvgPrice() / Math.max(1D, target)) * 0.3D);
            item.getMatchedReasons().add("人均 " + shop.getAvgPrice() + " 元，符合预算偏好");
        }
        if (profile != null && criteria.cuisine() != null && criteria.cuisine().include() != null) item.getMatchedReasons().add("菜系：" + profile.getCuisine());
        if (criteria.preferences() != null && profile != null) {
            for (DiningCriteria.SemanticPreference preference : criteria.preferences().values()) if (hasTag(profile, preference.criterion())) {
                score += 8D; item.getMatchedReasons().add("特征匹配：" + preference.criterion());
            }
        }
        if (criteria.preferences() != null) for (DiningCriteria.SemanticPreference preference : criteria.preferences().values())
            if (preference.importance() == DiningCriteria.Importance.MUST && "清淡".equals(preference.criterion()))
                item.getMatchedReasons().add("评价证据表明口味清淡");
        if (hasCoordinates(anchor)) {
            double km = distance(anchor.latitude(), anchor.longitude(), shop.getY(), shop.getX()); item.setDistanceKm(round(km));
            if (criteria.distance() != null && criteria.distance().hardMaxKm() != null) score += Math.max(0D, 20D * (1D - km / criteria.distance().hardMaxKm().doubleValue()));
        }
        if (profile != null && "LOW".equalsIgnoreCase(profile.getQueueLevel())) item.getMatchedReasons().add("排队风险低");
        if (reviews != null) for (AiReviewDocument review : reviews) { if (item.getEvidence().size() == 2) break; item.getEvidence().add(review.getContent()); score += 3D; }
        item.setScore(round(Math.min(100D, score))); return item;
    }
    private void addTags(DecisionRecommendation item, String raw) { if (raw == null) return; for (String tag : raw.split("[,，]")) if (!tag.isBlank() && item.getReferenceTags().size() < 8) item.getReferenceTags().add(tag.trim()); }
    private boolean hasTag(AiShopProfile profile, String tag) { return contains(profile.getSceneTags(), tag) || contains(profile.getAmbienceTags(), tag) || contains(profile.getSummary(), tag); }
    private boolean contains(String source, String expected) { return source != null && expected != null && source.contains(expected); }
    private void applyRelativeRanking(DecisionRecommendation item, Shop shop, List<RequirementChange.RelativePreference> preferences) {
        if (shop.getAvgPrice() == null) return;
        long lower = preferences.stream().filter(p -> p.dimension() == DiningCriteria.PreferenceDimension.PRICE && p.direction() == RequirementChange.Direction.LOWER).count();
        long higher = preferences.stream().filter(p -> p.dimension() == DiningCriteria.PreferenceDimension.PRICE && p.direction() == RequirementChange.Direction.HIGHER).count();
        double score = item.getScore() == null ? 0D : item.getScore();
        if (lower > 0) { score -= Math.min(24D, shop.getAvgPrice() * 0.12D * lower); item.getMatchedReasons().add("按更低人均价格优先排序"); }
        if (higher > 0) score += Math.min(12D, shop.getAvgPrice() * 0.04D * higher);
        item.setScore(round(Math.max(0D, score)));
    }
    private boolean requiresDistance(DiningCriteria c) { return c.distance() != null && c.distance().hardMaxKm() != null; }
    private boolean hasCoordinates(com.hmdp.ai.v2.plan.SearchAnchor a) { return a != null && a.latitude() != null && a.longitude() != null; }
    private double distance(Double lat1, Double lon1, Double lat2, Double lon2) {
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) return Double.POSITIVE_INFINITY;
        double lat = Math.toRadians(lat2 - lat1), lon = Math.toRadians(lon2 - lon1);
        double h = Math.sin(lat / 2) * Math.sin(lat / 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(lon / 2) * Math.sin(lon / 2);
        return 6371D * 2D * Math.atan2(Math.sqrt(h), Math.sqrt(1D - h));
    }
    private boolean isOpenAt(String openHours, String arrival) {
        if (arrival == null || arrival.isBlank() || openHours == null || openHours.isBlank()) return true;
        try { int target = minute(arrival); for (String range : openHours.replace(" ", "").split(",")) { String[] p = range.split("-"); if (p.length != 2) continue; int begin = minute(p[0]), end = minute(p[1]); if (end < begin ? target >= begin || target <= end : target >= begin && target <= end) return true; } return false; }
        catch (RuntimeException ignored) { return true; }
    }
    private int minute(String time) { String[] p = time.split(":"); return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]); }
    private double round(double x) { return Math.round(x * 100D) / 100D; }

    public record RetrievalResult(boolean supported, String detail, List<DecisionRecommendation> candidates, RetrievalMetrics metrics) {
        public RetrievalResult { candidates = candidates == null ? List.of() : List.copyOf(candidates); }
        static RetrievalResult unsupported(String message) { return new RetrievalResult(false, message, List.of(), RetrievalMetrics.empty(0)); }
        static RetrievalResult success(List<DecisionRecommendation> items, RetrievalMetrics metrics) { return new RetrievalResult(true, null, items, metrics); }
    }
    public record RetrievalMetrics(int initialCandidates, int hardMatchedCandidates, int finalCandidates,
                                   boolean semanticRetrievalUsed, long durationMs, long semanticDurationMs) {
        static RetrievalMetrics empty(long duration) { return new RetrievalMetrics(0, 0, 0, false, duration, 0); }
    }
}
