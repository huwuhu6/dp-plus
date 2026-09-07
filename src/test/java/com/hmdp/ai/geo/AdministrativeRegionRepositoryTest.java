package com.hmdp.ai.geo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdministrativeRegionRepositoryTest {
    private final AdministrativeRegionRepository repository = new ClasspathAdministrativeRegionRepository();

    @Test
    void exposesRegistryMetadataAndParentLookup() {
        assertTrue(!repository.completeDistrict());
        assertEquals("350102", repository.findChildren("350100", "鼓楼").get(0).getAdcode());
        assertEquals("福州市", repository.findByAdcode("350100").orElseThrow().getName());
    }
}
