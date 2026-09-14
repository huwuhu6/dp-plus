package com.hmdp.ai.v2.verification;

import com.hmdp.ai.dto.DecisionRecommendation;
import java.util.List;

public record VerificationReport(boolean hardChecksPassed, List<String> failedHardChecks,
                                 List<String> missingEvidenceCriteria,
                                 List<DecisionRecommendation> verifiedCandidates) {
    public VerificationReport {
        failedHardChecks = failedHardChecks == null ? List.of() : List.copyOf(failedHardChecks);
        missingEvidenceCriteria = missingEvidenceCriteria == null ? List.of() : List.copyOf(missingEvidenceCriteria);
        verifiedCandidates = verifiedCandidates == null ? List.of() : List.copyOf(verifiedCandidates);
    }
    public VerificationReport(boolean hardChecksPassed, List<String> failedHardChecks, List<String> missingEvidenceCriteria) {
        this(hardChecksPassed, failedHardChecks, missingEvidenceCriteria, List.of());
    }
}
