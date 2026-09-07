package com.hmdp.ai.geo;

import java.util.List;

/** Optional freshness provider for administrative data; never the primary authority. */
public interface AdministrativeRegionProvider {
    List<AdministrativeRegion> resolve(String keyword, String parentAdcode);
}
