package com.hmdp.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Outcome and diagnostics of one compact routing-fusion attempt. */
@Data
public class RoutingFusionV2Result {
    private RoutingSemanticIRV2 ir;
    private boolean valid;
    private boolean fallback;
    private String failureReason;
    private long durationMs;
    private boolean criteriaReusable;
    private List<String> validationErrors = new ArrayList<>();

    public static RoutingFusionV2Result disabled() {
        RoutingFusionV2Result result = new RoutingFusionV2Result();
        result.setFailureReason("MODE_OFF");
        return result;
    }

    public static RoutingFusionV2Result fallback(String reason, long durationMs) {
        RoutingFusionV2Result result = new RoutingFusionV2Result();
        result.setFallback(true);
        result.setFailureReason(reason);
        result.setDurationMs(durationMs);
        return result;
    }
}
