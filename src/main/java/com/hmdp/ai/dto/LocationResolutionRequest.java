package com.hmdp.ai.dto;

import lombok.Data;

@Data
public class LocationResolutionRequest {
    private String rawText;
    private LocationResolutionContext context;

    public LocationResolutionRequest() {
    }

    public LocationResolutionRequest(String rawText, LocationResolutionContext context) {
        this.rawText = rawText;
        this.context = context;
    }
}
