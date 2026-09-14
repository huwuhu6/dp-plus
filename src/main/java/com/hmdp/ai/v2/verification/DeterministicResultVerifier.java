package com.hmdp.ai.v2.verification;

import java.util.List;

public final class DeterministicResultVerifier implements ResultVerifier {
    @Override public VerificationReport verify(List<String> failures, List<String> evidenceGaps) {
        List<String> safeFailures = failures == null ? List.of() : List.copyOf(failures);
        return new VerificationReport(safeFailures.isEmpty(), safeFailures, evidenceGaps == null ? List.of() : List.copyOf(evidenceGaps));
    }
}
