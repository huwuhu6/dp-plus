package com.hmdp.ai.geo;

import java.util.List;
import java.util.Optional;

/** Read-only boundary around the administrative registry used by the resolver. */
public interface AdministrativeRegionRepository {
    List<AdministrativeRegion> findCandidates(String alias);

    List<AdministrativeRegion> findChildren(String parentAdcode, String alias);

    Optional<AdministrativeRegion> findByAdcode(String adcode);

    boolean completeProvinceCity();

    boolean completeDistrict();
}
