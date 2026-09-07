package com.hmdp.ai.geo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.DecisionConstraints;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic administrative entity resolution. POIs and device location are separate concerns.
 * Knowing that a name is a DISTRICT does not identify which district it is; identity requires
 * a parent context, a complete registry, or an authoritative provider result.
 */
@Service
public class AdministrativeRegionResolver {
    private final AdministrativeRegionRepository repository;
    private final AdministrativeRegionProvider provider;

    public AdministrativeRegionResolver() { this(new ClasspathAdministrativeRegionRepository(), (AdministrativeRegionProvider) null); }
    public AdministrativeRegionResolver(ObjectMapper objectMapper) { this(new ClasspathAdministrativeRegionRepository(objectMapper), (AdministrativeRegionProvider) null); }
    public AdministrativeRegionResolver(AdministrativeRegionRepository repository) { this(repository, (AdministrativeRegionProvider) null); }
    public AdministrativeRegionResolver(AdministrativeRegionRepository repository, AdministrativeRegionProvider provider) {
        this.repository = repository;
        this.provider = provider;
    }

    @Autowired
    public AdministrativeRegionResolver(AdministrativeRegionRepository repository,
                                        ObjectProvider<AdministrativeRegionProvider> provider) {
        this(repository, provider.getIfAvailable());
    }

    public AdministrativeResolution resolve(String rawQuery, DecisionConstraints context) {
        String query = normalize(rawQuery);
        if (query.isEmpty()) return AdministrativeResolution.notFound();
        AdministrativeResolution local = resolveLocal(query, context);
        if (local.status() != AdministrativeResolution.Status.NOT_FOUND) return local;
        if (provider == null || !looksLikeAdministrativeQuery(query)) return local;
        try {
            List<AdministrativeRegion> remote = provider.resolve(query, parentAdcode(context));
            return classify(remote == null ? List.of() : remote, context);
        } catch (RuntimeException ignored) {
            // A failed freshness provider must degrade to unresolved clarification, never a guessed region.
            return AdministrativeResolution.notFound();
        }
    }

    /**
     * Validates an administrative candidate produced by an untrusted extractor.
     *
     * The extractor may know the canonical name (for example, "福州市") while
     * the user message only contains an alias.  That alias is accepted only when
     * it is independently grounded in the message; an alias embedded in a POI
     * such as "福州大学" is not an administrative mention.  Once grounded, the
     * ordinary resolver remains the sole authority for identity and hierarchy.
     */
    public AdministrativeResolution resolveHint(String rawQuery, DecisionConstraints hint,
                                                DecisionConstraints context) {
        if (hint == null) return AdministrativeResolution.notFound();
        String query = normalize(rawQuery);
        if (query.isEmpty()) return AdministrativeResolution.notFound();

        StringBuilder grounded = new StringBuilder();
        appendGroundedHint(grounded, query, hint.getTargetProvince());
        appendGroundedHint(grounded, query, hint.getTargetCity());
        appendGroundedHint(grounded, query, hint.getTargetDistrict());
        if (grounded.length() == 0) return AdministrativeResolution.notFound();
        return resolve(grounded.toString(), context);
    }

    private void appendGroundedHint(StringBuilder grounded, String query, String hintedName) {
        String canonical = normalize(hintedName);
        if (!canonical.isEmpty() && independentlyMentioned(query, canonical)) grounded.append(canonical);
    }

    private boolean independentlyMentioned(String query, String canonical) {
        if (query.contains(canonical)) return true;
        String alias = stripSuffix(canonical);
        // A suffixless alias is useful for natural input ("连江"), but an
        // alias occurring inside a known POI/landmark is not sufficient evidence.
        return !alias.isEmpty() && query.contains(alias) && !looksLikePoi(query);
    }

    private AdministrativeResolution resolveLocal(String query, DecisionConstraints context) {
        List<AdministrativeRegion> districts = repository.findCandidates(query).stream()
                .filter(region -> region.getLevel() == AdministrativeLevel.DISTRICT)
                .filter(region -> matchesQuery(query, region))
                .filter(region -> matchesParentContext(region, context))
                .sorted(Comparator.comparing(AdministrativeRegion::getName))
                .toList();
        if (!districts.isEmpty()) {
            List<AdministrativeRegion> explicitParentMatches = districts.stream()
                    .filter(region -> hasParentInQuery(query, region)).toList();
            if (!explicitParentMatches.isEmpty()) districts = explicitParentMatches;
            boolean parentKnown = hasParentInQuery(query, districts.get(0)) || hasDistrictContext(context);
            if (districts.size() == 1 && (parentKnown || repository.completeDistrict())) {
                return resolved(districts);
            }
            return new AdministrativeResolution(AdministrativeResolution.Status.AMBIGUOUS, districts);
        }
        List<AdministrativeRegion> hierarchy = repository.findCandidates(query).stream()
                .filter(region -> region.getLevel() != AdministrativeLevel.DISTRICT)
                .filter(region -> matchesHierarchyQuery(query, region))
                .toList();
        if (!hierarchy.isEmpty()) {
            int mostSpecific = hierarchy.stream().mapToInt(region -> region.getLevel().ordinal()).max().orElse(0);
            hierarchy = hierarchy.stream().filter(region -> region.getLevel().ordinal() == mostSpecific).toList();
        }
        return classify(hierarchy, context);
    }

    private AdministrativeResolution classify(List<AdministrativeRegion> candidates, DecisionConstraints context) {
        List<AdministrativeRegion> filtered = candidates.stream().filter(region -> matchesParentContext(region, context))
                .sorted(Comparator.comparing(AdministrativeRegion::getName, Comparator.nullsLast(String::compareTo))).toList();
        if (filtered.size() == 1) {
            AdministrativeRegion only = filtered.get(0);
            // A remote district name without a trusted city hierarchy is only a level
            // hint, not an executable identity.  Never turn a single provider row into
            // a nationwide-unique district by accident.
            if (only.getLevel() == AdministrativeLevel.DISTRICT
                    && !hasText(only.getCity())) return AdministrativeResolution.notFound();
            return resolved(filtered);
        }
        if (filtered.size() > 1) return new AdministrativeResolution(AdministrativeResolution.Status.AMBIGUOUS, filtered);
        return AdministrativeResolution.notFound();
    }

    private AdministrativeResolution resolved(List<AdministrativeRegion> candidates) {
        return new AdministrativeResolution(AdministrativeResolution.Status.RESOLVED, candidates);
    }

    private boolean matchesQuery(String query, AdministrativeRegion region) {
        String name = normalize(region.getName());
        String alias = stripSuffix(name);
        return query.contains(name) || (!alias.isEmpty() && query.contains(alias));
    }

    private boolean matchesHierarchyQuery(String query, AdministrativeRegion region) {
        if (looksLikePoi(query)) return false;
        String name = normalize(region.getName());
        String alias = stripSuffix(name);
        return query.equals(name) || query.equals(alias) || query.contains(name) || (!alias.isEmpty() && query.contains(alias));
    }

    private boolean matchesParentContext(AdministrativeRegion region, DecisionConstraints context) {
        if (context == null) return true;
        String targetCity = normalize(context.getTargetCity());
        String targetProvince = normalize(context.getTargetProvince());
        return (targetCity.isEmpty() || sameName(region.getCity(), targetCity))
                && (targetProvince.isEmpty() || sameName(region.getProvince(), targetProvince));
    }

    private boolean hasParentInQuery(String query, AdministrativeRegion region) {
        return (hasText(region.getCity()) && containsName(query, region.getCity()))
                || (hasText(region.getProvince()) && containsName(query, region.getProvince()));
    }

    private boolean hasDistrictContext(DecisionConstraints context) {
        return context != null && (hasText(context.getTargetCity()) || hasText(context.getTargetProvince()));
    }

    private boolean looksLikeAdministrativeQuery(String query) {
        return query.contains("区") || query.contains("县") || query.contains("自治州") || query.contains("地区")
                || query.contains("省") || query.contains("市");
    }

    private boolean looksLikePoi(String query) {
        return query.contains("大学") || query.contains("景区") || query.contains("商场") || query.contains("广场")
                || query.contains("机场") || query.contains("车站") || query.contains("地铁") || query.contains("路")
                || query.contains("街") || query.contains("公园") || query.contains("大厦");
    }

    private String parentAdcode(DecisionConstraints context) {
        if (context == null || !hasText(context.getTargetCity())) return null;
        return repository.findCandidates(context.getTargetCity()).stream().filter(region -> region.getLevel() == AdministrativeLevel.CITY)
                .map(AdministrativeRegion::getAdcode).findFirst().orElse(null);
    }

    private boolean containsName(String query, String value) {
        String name = normalize(value);
        return !name.isEmpty() && (query.contains(name) || query.contains(stripSuffix(name)));
    }

    private boolean sameName(String left, String right) {
        return normalize(left).equals(right) || stripSuffix(normalize(left)).equals(stripSuffix(right));
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\s\\p{Punct}，。！？：；、“”‘’（）【】]+", "").trim().toLowerCase(Locale.ROOT);
    }

    private String stripSuffix(String value) {
        for (String suffix : List.of("省", "市", "区", "县", "自治州", "地区", "盟")) {
            if (value.endsWith(suffix) && value.length() > suffix.length()) return value.substring(0, value.length() - suffix.length());
        }
        return value;
    }

    private boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }
}
