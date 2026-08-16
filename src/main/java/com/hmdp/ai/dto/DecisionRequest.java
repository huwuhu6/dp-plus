package com.hmdp.ai.dto;

import lombok.Data;

@Data
public class DecisionRequest {
    private String query;
    private Double latitude;
    private Double longitude;
    private String province;
    private String city;
    private String district;
    private String locationStatus = "MISSING";
    /** Whether a location restored from conversation state should constrain this search to nearby shops. */
    private Boolean useLocationScope = false;
    private Integer maxCandidates = 3;
}
