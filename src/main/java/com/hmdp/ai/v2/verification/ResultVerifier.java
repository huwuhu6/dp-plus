package com.hmdp.ai.v2.verification;

import java.util.List;

/** Verifier only reports deterministic checks and evidence gaps; it is deliberately not a PolicyEngine or state writer. */
public interface ResultVerifier {
    VerificationReport verify(List<String> deterministicHardCheckFailures, List<String> missingEvidenceCriteria);
}
