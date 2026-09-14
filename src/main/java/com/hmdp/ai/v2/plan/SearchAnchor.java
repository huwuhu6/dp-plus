package com.hmdp.ai.v2.plan;
/** Resolved execution anchor, distinct from the user's location criterion and frozen with the plan. */
public record SearchAnchor(String poiId, String canonicalName, Double latitude, Double longitude,
                           String province, String city, String district, AnchorSource source) {
    public enum AnchorSource { DEVICE, NAMED_LOCATION, POI }
}
