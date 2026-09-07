package com.hmdp.ai.geo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.DecisionConstraints;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Resolves administrative names from a versioned registry, independent of LLM extraction. */
@Service
public class AdministrativeRegionResolver {
    private final List<AdministrativeRegion> regions;

    public AdministrativeRegionResolver() {
        this(new ObjectMapper());
    }

    public AdministrativeRegionResolver(ObjectMapper objectMapper) {
        this.regions = load(objectMapper);
    }

    public AdministrativeResolution resolve(String rawQuery, DecisionConstraints context) {
        String query = normalize(rawQuery);
        if (query.isEmpty()) return AdministrativeResolution.notFound();

        List<AdministrativeRegion> districtMatches = regions.stream()
                .filter(region -> region.getLevel() == AdministrativeLevel.DISTRICT)
                .filter(region -> matchesDistrict(query, region, context))
                .filter(region -> matchesParentContext(region, context))
                .sorted(Comparator.comparing(AdministrativeRegion::getName))
                .toList();
        if (districtMatches.size() == 1) return new AdministrativeResolution(AdministrativeResolution.Status.RESOLVED, districtMatches);
        if (districtMatches.size() > 1) return new AdministrativeResolution(AdministrativeResolution.Status.AMBIGUOUS, districtMatches);

        List<AdministrativeRegion> hierarchyMatches = regions.stream()
                .filter(region -> region.getLevel() != AdministrativeLevel.DISTRICT)
                .filter(region -> matchesCanonicalOrAlias(query, region))
                .toList();
        if (hierarchyMatches.size() == 1) return new AdministrativeResolution(AdministrativeResolution.Status.RESOLVED, hierarchyMatches);
        if (hierarchyMatches.size() > 1) return new AdministrativeResolution(AdministrativeResolution.Status.AMBIGUOUS, hierarchyMatches);
        return AdministrativeResolution.notFound();
    }

    private boolean matchesDistrict(String query, AdministrativeRegion region, DecisionConstraints context) {
        String canonical = normalize(region.getName());
        String alias = stripSuffix(canonical);
        return query.contains(canonical) || (!alias.isEmpty() && query.equals(alias))
                || (!alias.isEmpty() && query.contains(alias)
                && ((context != null && hasText(context.getTargetCity())) || containsAdministrativeCue(query)));
    }

    private boolean matchesCanonicalOrAlias(String query, AdministrativeRegion region) {
        String canonical = normalize(region.getName());
        String alias = stripSuffix(canonical);
        return query.equals(canonical) || query.equals(alias)
                || (region.getLevel() == AdministrativeLevel.PROVINCE && query.contains(canonical))
                || (region.getLevel() == AdministrativeLevel.CITY && query.contains(canonical)
                && (query.contains("市") || query.contains("省")));
    }

    private boolean matchesParentContext(AdministrativeRegion region, DecisionConstraints context) {
        if (context == null || !hasText(context.getTargetCity())) return true;
        String city = normalize(context.getTargetCity());
        return normalize(region.getCity()).equals(city) || stripSuffix(normalize(region.getCity())).equals(stripSuffix(city));
    }

    private boolean containsAdministrativeCue(String query) {
        return query.contains("区") || query.contains("县") || query.contains("内") || query.contains("附近")
                || query.contains("有什么") || query.contains("吃") || query.contains("找");
    }

    private List<AdministrativeRegion> load(ObjectMapper objectMapper) {
        try (InputStream input = new ClassPathResource("geo/administrative-regions.json").getInputStream()) {
            return objectMapper.readValue(input, new TypeReference<List<AdministrativeRegion>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load administrative region registry", e);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim().toLowerCase(Locale.ROOT);
    }

    private String stripSuffix(String value) {
        if (value == null) return "";
        for (String suffix : List.of("省", "市", "区", "县", "自治州", "地区", "盟")) {
            if (value.endsWith(suffix) && value.length() > suffix.length()) return value.substring(0, value.length() - suffix.length());
        }
        return value;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
