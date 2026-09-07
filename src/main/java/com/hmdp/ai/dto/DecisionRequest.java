package com.hmdp.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DecisionRequest {
    private String query;
    private Double latitude;
    private Double longitude;
    private String province;
    private String city;
    private String district;
    /** Canonical named POI used as the search center; request-scoped, not durable criteria. */
    private String locationName;
    private String locationStatus = "MISSING";
    /** Whether a location restored from conversation state should constrain this search to nearby shops. */
    private Boolean useLocationScope = false;
    private Integer maxCandidates = 3;
    /** Candidates shown in the immediately preceding recommendation and excluded during a refresh. */
    private List<Long> excludeShopIds = new ArrayList<Long>();
}
