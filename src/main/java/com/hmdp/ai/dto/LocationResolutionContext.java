package com.hmdp.ai.dto;

import lombok.Data;

/** Request-scoped context used only to rank/ground named-location candidates. */
@Data
public class LocationResolutionContext {
    private Double deviceLatitude;
    private Double deviceLongitude;
    private String activeProvince;
    private String activeCity;
    private String activeDistrict;
    private String activeCityAdcode;
    private String activeDistrictAdcode;
    private String currentNamedLocation;
}
