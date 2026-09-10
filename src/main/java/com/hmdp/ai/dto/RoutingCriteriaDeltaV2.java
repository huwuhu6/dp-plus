package com.hmdp.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** Raw criteria fact carried by routing fusion when the same turn may start a decision. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RoutingCriteriaDeltaV2 {
    private CriteriaDeltaOperation.Field field;
    private CriteriaDeltaOperation.Operation operation;
    private String rawValue;
    private String evidenceText;
}
