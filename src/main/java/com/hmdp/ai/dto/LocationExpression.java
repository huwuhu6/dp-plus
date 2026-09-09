package com.hmdp.ai.dto;

import lombok.Data;

/** Raw location mention; AdministrativeRegionResolver remains the authority. */
@Data
public class LocationExpression {
    private String rawText;
    private Boolean reset;
    private SemanticEvidence evidence;
}
