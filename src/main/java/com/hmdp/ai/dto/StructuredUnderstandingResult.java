package com.hmdp.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Outcome of one structured-understanding attempt, including fail-closed diagnostics. */
@Data
public class StructuredUnderstandingResult {
    private TurnSemanticIR ir;
    private boolean valid;
    private boolean fallback;
    private String failureReason;
    private long durationMs;
    private List<String> validationErrors = new ArrayList<>();

    public static StructuredUnderstandingResult disabled() {
        StructuredUnderstandingResult result = new StructuredUnderstandingResult();
        result.setValid(false);
        result.setFallback(false);
        result.setFailureReason("MODE_OFF");
        return result;
    }

    public static StructuredUnderstandingResult fallback(String reason, long durationMs) {
        StructuredUnderstandingResult result = new StructuredUnderstandingResult();
        result.setFallback(true);
        result.setFailureReason(reason);
        result.setDurationMs(durationMs);
        return result;
    }
}
