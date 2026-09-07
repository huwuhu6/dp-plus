package com.hmdp.ai.geo;

import com.hmdp.ai.dto.DecisionConstraints;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;
import java.util.Optional;

class AdministrativeRegionResolverTest {
    private final AdministrativeRegionResolver resolver = new AdministrativeRegionResolver();

    @Test
    void resolvesDistrictAgainstCurrentCityContext() {
        DecisionConstraints context = new DecisionConstraints();
        context.setTargetCity("福州市");

        AdministrativeResolution result = resolver.resolve("鼓楼呢？", context);

        assertEquals(AdministrativeResolution.Status.RESOLVED, result.status());
        assertEquals("350102", result.candidates().get(0).getAdcode());
    }

    @Test
    void keepsNakedDistrictAmbiguousWithoutParent() {
        AdministrativeResolution result = resolver.resolve("鼓楼有什么吃的", null);

        assertEquals(AdministrativeResolution.Status.AMBIGUOUS, result.status());
        assertEquals(3, result.candidates().size());
    }

    @Test
    void doesNotTreatLandmarkAsAdministrativeDistrict() {
        AdministrativeResolution result = resolver.resolve("福州大学附近吃什么", null);

        assertEquals(AdministrativeResolution.Status.NOT_FOUND, result.status());
    }

    @Test
    void resolvesUniqueCountyWithParentHierarchy() {
        AdministrativeResolution result = resolver.resolve("福建福州闽侯县有什么吃的", null);

        assertEquals(AdministrativeResolution.Status.RESOLVED, result.status());
        assertEquals("闽侯县", result.candidates().get(0).getName());
        assertEquals("福州市", result.candidates().get(0).getCity());
    }

    @Test
    void doesNotTreatPartialRegistrySingleDistrictAsNationwideUnique() {
        AdministrativeResolution result = resolver.resolve("西湖区", null);

        assertEquals(AdministrativeResolution.Status.AMBIGUOUS, result.status());
        assertEquals("330106", result.candidates().get(0).getAdcode());
    }

    @Test
    void districtSuffixWithParentStillResolves() {
        AdministrativeResolution result = resolver.resolve("福州鼓楼区", null);

        assertEquals(AdministrativeResolution.Status.RESOLVED, result.status());
        assertEquals("福建省", result.candidates().get(0).getProvince());
        assertEquals("福州市", result.candidates().get(0).getCity());
    }

    @Test
    void rejectsAdministrativeAliasEmbeddedInPoiWhenValidatingModelHint() {
        DecisionConstraints hint = new DecisionConstraints();
        hint.setTargetCity("福州市");

        AdministrativeResolution result = resolver.resolveHint("福州大学附近有什么吃的", hint, null);

        assertEquals(AdministrativeResolution.Status.NOT_FOUND, result.status());
    }

    @Test
    void acceptsFullAdministrativeNameAlongsidePoi() {
        DecisionConstraints hint = new DecisionConstraints();
        hint.setTargetCity("福州市");

        AdministrativeResolution result = resolver.resolveHint("福州市福州大学附近有什么吃的", hint, null);

        assertEquals(AdministrativeResolution.Status.RESOLVED, result.status());
        assertEquals("福州市", result.candidates().get(0).getCity());
    }

    @Test
    void rejectsAnotherPoiPrefixInsteadOfTreatingItAsDistrict() {
        DecisionConstraints hint = new DecisionConstraints();
        hint.setTargetDistrict("仓山区");

        AdministrativeResolution result = resolver.resolveHint("仓山公园附近有什么吃的", hint, null);

        assertEquals(AdministrativeResolution.Status.NOT_FOUND, result.status());
    }

    @Test
    void geographicPrefixAcceptsCityAliasButNotPoiName() {
        AdministrativeResolution city = resolver.resolveGeographicContextPrefix("福州");
        assertEquals(AdministrativeResolution.Status.RESOLVED, city.status());
        assertEquals(AdministrativeLevel.CITY, city.candidates().get(0).getLevel());

        AdministrativeResolution poi = resolver.resolveGeographicContextPrefix("福州大学");
        assertEquals(AdministrativeResolution.Status.NOT_FOUND, poi.status());
    }

    @Test
    void geographicPrefixUsesProviderForLocalAliasMiss() {
        AdministrativeRegion city = new AdministrativeRegion();
        city.setName("北京市");
        city.setLevel(AdministrativeLevel.CITY);
        city.setAdcode("110100");
        city.setProvince("北京市");
        AdministrativeRegionRepository repository = new AdministrativeRegionRepository() {
            public List<AdministrativeRegion> findCandidates(String alias) { return List.of(); }
            public List<AdministrativeRegion> findChildren(String parentAdcode, String alias) { return List.of(); }
            public Optional<AdministrativeRegion> findByAdcode(String adcode) { return Optional.empty(); }
            public boolean completeProvinceCity() { return false; }
            public boolean completeDistrict() { return false; }
        };
        AdministrativeRegionProvider provider = (keyword, parentAdcode) ->
                "北京".equals(keyword) ? List.of(city) : List.of();

        AdministrativeResolution result = new AdministrativeRegionResolver(repository, provider)
                .resolveGeographicContextPrefix("北京");

        assertEquals(AdministrativeResolution.Status.RESOLVED, result.status());
        assertEquals("110100", result.candidates().get(0).getAdcode());
    }
}
