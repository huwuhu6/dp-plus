package com.hmdp.ai.geo;

import com.hmdp.ai.dto.DecisionConstraints;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
