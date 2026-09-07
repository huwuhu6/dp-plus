package com.hmdp.ai.dto;

import lombok.Data;

@Data
public class DecisionFollowUpRequest {
    private String selectedOptionId;
    private String message;
    private Double latitude;
    private Double longitude;
    private String province;
    private String city;
    private String district;
    /** Canonical named POI selected during location clarification. */
    private String locationName;
}
