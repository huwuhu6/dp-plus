package com.hmdp.ai.service;

import com.hmdp.ai.dto.ResolvedEvidence;
import org.springframework.stereotype.Component;

/** Deterministically grounds evidenceText using Java String indexing. */
@Component
public class EvidenceGrounder {
    public ResolvedEvidence ground(String originalMessage, String evidenceText) {
        String original = originalMessage == null ? "" : originalMessage;
        if (evidenceText == null || evidenceText.isBlank()) {
            return ResolvedEvidence.of(evidenceText, null, null, ResolvedEvidence.Status.MISSING);
        }
        int first = original.indexOf(evidenceText);
        if (first < 0) return ResolvedEvidence.of(evidenceText, null, null, ResolvedEvidence.Status.NOT_FOUND);
        int second = original.indexOf(evidenceText, first + Math.max(1, evidenceText.length()));
        if (second >= 0) return ResolvedEvidence.of(evidenceText, null, null, ResolvedEvidence.Status.AMBIGUOUS);
        return ResolvedEvidence.of(evidenceText, first, first + evidenceText.length(), ResolvedEvidence.Status.RESOLVED);
    }
}
