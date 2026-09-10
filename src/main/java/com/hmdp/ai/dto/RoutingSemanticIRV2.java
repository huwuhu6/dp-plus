package com.hmdp.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Compact linguistic result used only for ROUTING_ESCALATION.
 * It deliberately has no references, shop facts, query objects or entity ids.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RoutingSemanticIRV2 {
    private String version = "v2";
    private List<RoutingSemanticActV2> acts = new ArrayList<>();
    private List<RoutingCriteriaDeltaV2> criteriaDelta = new ArrayList<>();
    private RoutingLocationExpressionV2 locationExpression;
    private List<String> ambiguities = new ArrayList<>();
}
