package com.hmdp.ai.geo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class AdministrativeRegionProviderTest {
    @Test
    void parsesOfficialDistrictResponseAndEnrichesLocalHierarchy() throws Exception {
        AmapAdministrativeRegionProvider provider = new AmapAdministrativeRegionProvider();
        List<AdministrativeRegion> regions = provider.parseResponse("""
                {"status":"1","info":"OK","districts":[
                  {"citycode":"0591","adcode":"350102","name":"鼓楼区",
                   "center":"119.3037,26.0827","level":"district","districts":[]}
                ]}
                """);

        assertEquals(1, regions.size());
        AdministrativeRegion region = regions.get(0);
        assertEquals("350102", region.getAdcode());
        assertEquals("鼓楼区", region.getName());
        assertEquals(AdministrativeLevel.DISTRICT, region.getLevel());
        assertEquals("福建省", region.getProvince());
        assertEquals("福州市", region.getCity());
        assertEquals("350100", region.getParentAdcode());
    }

    @Test
    void malformedResponseIsSafe() {
        AmapAdministrativeRegionProvider provider = new AmapAdministrativeRegionProvider();
        assertDoesNotThrow(() -> provider.parseResponse("not-json"));
    }

    @Test
    void resolvesProviderResultAndDegradesOnProviderFailure() {
        AdministrativeRegion region = new AdministrativeRegion();
        region.setAdcode("999999");
        region.setLevel(AdministrativeLevel.DISTRICT);
        region.setName("新安区");
        region.setProvince("测试省");
        region.setCity("测试市");
        region.setDistrict("新安区");
        AdministrativeRegionProvider provider = (keyword, parent) -> List.of(region);
        AdministrativeRegionResolver resolver = new AdministrativeRegionResolver(new ClasspathAdministrativeRegionRepository(), provider);
        assertEquals(AdministrativeResolution.Status.RESOLVED, resolver.resolve("新安区", null).status());

        AdministrativeRegionResolver failed = new AdministrativeRegionResolver(new ClasspathAdministrativeRegionRepository(),
                (keyword, parent) -> { throw new IllegalStateException("timeout"); });
        assertDoesNotThrow(() -> failed.resolve("新安区", null));
        assertEquals(AdministrativeResolution.Status.NOT_FOUND, failed.resolve("新安区", null).status());
    }

    @Test
    void doesNotResolveRemoteDistrictWithoutParentHierarchy() {
        AdministrativeRegion region = new AdministrativeRegion();
        region.setAdcode("999998");
        region.setLevel(AdministrativeLevel.DISTRICT);
        region.setName("新城区");
        region.setDistrict("新城区");
        AdministrativeRegionResolver resolver = new AdministrativeRegionResolver(
                new ClasspathAdministrativeRegionRepository(), (keyword, parent) -> List.of(region));

        assertEquals(AdministrativeResolution.Status.NOT_FOUND, resolver.resolve("新城区", null).status());
    }
}
