package com.hmdp.ai.dto;

import lombok.Data;

@Data
public class LocationResolutionRequest {
    private String rawText;
    private LocationResolutionContext context;
    /** Request-scoped entity hint; never persisted in Working Memory. */
    private String entityTypeHint;

    public LocationResolutionRequest() {
    }

    public LocationResolutionRequest(String rawText, LocationResolutionContext context) {
        this.rawText = rawText;
        this.context = context;
    }

    public LocationResolutionRequest(String rawText, LocationResolutionContext context, String entityTypeHint) {
        this.rawText = rawText;
        this.context = context;
        this.entityTypeHint = entityTypeHint;
    }
}
