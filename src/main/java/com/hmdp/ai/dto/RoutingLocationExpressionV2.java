package com.hmdp.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** Raw location language only; AdministrativeRegionResolver remains authoritative. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RoutingLocationExpressionV2 {
    private String rawText;
    private Boolean reset;
    private String evidenceText;
}
