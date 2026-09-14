package com.hmdp.ai.v2.verification;

import java.util.List;

public record VerificationReport(boolean hardChecksPassed, List<String> failedHardChecks,
                                 List<String> missingEvidenceCriteria) {
    public VerificationReport { failedHardChecks = List.copyOf(failedHardChecks); missingEvidenceCriteria = List.copyOf(missingEvidenceCriteria); }
}
