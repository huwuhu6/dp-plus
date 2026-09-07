package com.hmdp.ai.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Loads the versioned local registry once; it is not coupled to tbl_shop. */
@Repository
public class ClasspathAdministrativeRegionRepository implements AdministrativeRegionRepository {
    private final List<AdministrativeRegion> regions;
    private final boolean completeProvinceCity;
    private final boolean completeDistrict;

    public ClasspathAdministrativeRegionRepository() {
        this(new ObjectMapper());
    }

    public ClasspathAdministrativeRegionRepository(ObjectMapper objectMapper) {
        try (InputStream input = new ClassPathResource("geo/administrative-regions.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(input);
            JsonNode regionNode = root.isArray() ? root : root.path("regions");
            List<AdministrativeRegion> loaded = new ArrayList<>();
            if (regionNode.isArray()) {
                for (JsonNode node : regionNode) loaded.add(objectMapper.treeToValue(node, AdministrativeRegion.class));
            }
            this.regions = List.copyOf(loaded);
            this.completeProvinceCity = root.isObject() && root.path("completeProvinceCity").asBoolean(false);
            this.completeDistrict = root.isObject() && root.path("completeDistrict").asBoolean(false);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load administrative region registry", e);
        }
    }

    @Override
    public List<AdministrativeRegion> findCandidates(String alias) {
        String normalized = normalize(alias);
        if (normalized.isEmpty()) return List.of();
        return regions.stream().filter(region -> matches(region, normalized)).toList();
    }

    @Override
    public List<AdministrativeRegion> findChildren(String parentAdcode, String alias) {
        String normalized = normalize(alias);
        return regions.stream()
                .filter(region -> normalize(parentAdcode).equals(normalize(region.getParentAdcode())))
                .filter(region -> matches(region, normalized))
                .toList();
    }

    @Override
    public Optional<AdministrativeRegion> findByAdcode(String adcode) {
        String normalized = normalize(adcode);
        return regions.stream().filter(region -> normalize(region.getAdcode()).equals(normalized)).findFirst();
    }

    @Override public boolean completeProvinceCity() { return completeProvinceCity; }
    @Override public boolean completeDistrict() { return completeDistrict; }

    private boolean matches(AdministrativeRegion region, String alias) {
        String name = normalize(region.getName());
        String shortName = stripSuffix(name);
        return name.equals(alias) || shortName.equals(stripSuffix(alias))
                || alias.contains(name) || (!shortName.isEmpty() && alias.contains(shortName));
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim().toLowerCase(Locale.ROOT);
    }

    private String stripSuffix(String value) {
        for (String suffix : List.of("省", "市", "区", "县", "自治州", "地区", "盟")) {
            if (value.endsWith(suffix) && value.length() > suffix.length()) return value.substring(0, value.length() - suffix.length());
        }
        return value;
    }
}
