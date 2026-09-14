package com.hmdp.ai.v2.runtime;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.ai.entity.AiShopProfile;
import com.hmdp.ai.mapper.AiShopProfileMapper;
import com.hmdp.ai.util.CuisineCanonicalizer;
import com.hmdp.ai.v2.plan.ExecutionAction;
import com.hmdp.ai.v2.plan.SearchSpec;
import com.hmdp.ai.v2.semantic.DiningCriteria;
import com.hmdp.ai.v2.semantic.RequirementChange;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import java.util.*;

/** Stateless retrieval boundary. It deliberately has no decision session, message, or WorkingMemory dependency. */
@Service
public class ShopRetrievalEngine {
    @Resource private ShopMapper shopMapper;
    @Resource private AiShopProfileMapper profileMapper;

    public List<Long> retrieve(SearchSpec spec) {
        if (spec.kind() == ExecutionAction.SearchKind.SIMILAR && spec.anchor() == null)
            throw new IllegalArgumentException("similar search requires a grounded anchor");
        QueryWrapper<Shop> query = new QueryWrapper<>();
        DiningCriteria.LocationCriteria location = spec.criteria().location();
        if (location != null && location.city() != null && !location.city().isBlank()) query.eq("city", location.city());
        if (location != null && location.district() != null && !location.district().isBlank()) query.eq("district", location.district());
        if (!spec.excludedShopIds().isEmpty()) query.notIn("id", spec.excludedShopIds());
        List<Shop> shops = shopMapper.selectList(query);
        Map<Long, AiShopProfile> profiles = new HashMap<>();
        for (AiShopProfile profile : profileMapper.selectList(null)) profiles.put(profile.getShopId(), profile);
        Comparator<Shop> ranking = Comparator.comparingDouble((Shop shop) -> score(shop, spec, profiles.get(shop.getId()))).reversed()
                .thenComparing(Shop::getId);
        return shops.stream().filter(shop -> hardMatch(shop, profiles.get(shop.getId()), spec)).sorted(ranking)
                .limit(spec.count() > 0 ? spec.count() : 3).map(Shop::getId).toList();
    }
    private boolean hardMatch(Shop shop, AiShopProfile profile, SearchSpec spec) {
        DiningCriteria criteria = spec.criteria();
        if (criteria.budget() != null && criteria.budget().hardMax() != null && (shop.getAvgPrice() == null
                || criteria.budget().hardMax().longValue() < shop.getAvgPrice())) return false;
        if (criteria.cuisine() != null && criteria.cuisine().include() != null && !matches(profile, criteria.cuisine().include())) return false;
        if (criteria.cuisine() != null) for (String excluded : criteria.cuisine().exclude()) if (matches(profile, excluded)) return false;
        if (criteria.distance() != null && criteria.distance().hardMaxKm() != null && spec.searchAnchor() != null
                && distance(spec.searchAnchor().latitude(), spec.searchAnchor().longitude(), shop.getY(), shop.getX()) > criteria.distance().hardMaxKm().doubleValue()) return false;
        return true;
    }
    private double score(Shop shop, SearchSpec spec, AiShopProfile ignored) {
        double score = shop.getScore() == null ? 0D : shop.getScore();
        boolean preferLowerPrice = spec.relativePreferences().stream().anyMatch(p -> p.dimension() == DiningCriteria.PreferenceDimension.PRICE
                && p.direction() == RequirementChange.Direction.LOWER);
        if (preferLowerPrice && shop.getAvgPrice() != null) score -= shop.getAvgPrice() / 10D;
        if (spec.kind() == ExecutionAction.SearchKind.SIMILAR && spec.anchor() != null && shop.getId().equals(spec.anchor().shopId())) return Double.NEGATIVE_INFINITY;
        return score;
    }
    private boolean matches(AiShopProfile profile, String expected) {
        if (profile == null || profile.getCuisine() == null) return false;
        String canonical = CuisineCanonicalizer.canonicalize(expected);
        return Arrays.stream(profile.getCuisine().split(",")).map(CuisineCanonicalizer::canonicalize).anyMatch(canonical::equals);
    }
    private double distance(Double aLat, Double aLon, Double bLat, Double bLon) {
        if (aLat == null || aLon == null || bLat == null || bLon == null) return Double.POSITIVE_INFINITY;
        double lat = Math.toRadians(bLat - aLat), lon = Math.toRadians(bLon - aLon);
        double h = Math.sin(lat / 2) * Math.sin(lat / 2) + Math.cos(Math.toRadians(aLat)) * Math.cos(Math.toRadians(bLat)) * Math.sin(lon / 2) * Math.sin(lon / 2);
        return 6371D * 2D * Math.atan2(Math.sqrt(h), Math.sqrt(1D - h));
    }
}
