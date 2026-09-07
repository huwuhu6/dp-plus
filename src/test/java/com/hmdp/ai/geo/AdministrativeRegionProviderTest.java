package com.hmdp.ai.geo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class AdministrativeRegionProviderTest {
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
}
