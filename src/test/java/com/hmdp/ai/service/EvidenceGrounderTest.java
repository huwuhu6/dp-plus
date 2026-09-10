package com.hmdp.ai.service;

import com.hmdp.ai.dto.ResolvedEvidence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvidenceGrounderTest {
    private final EvidenceGrounder grounder = new EvidenceGrounder();

    @Test
    void uniqueChineseTextGetsJavaAuthoritativeSpan() {
        ResolvedEvidence result = grounder.ground("帮我找附近的烤肉店", "烤肉");

        assertEquals(ResolvedEvidence.Status.RESOLVED, result.getStatus());
        assertEquals(6, result.getStart());
        assertEquals(8, result.getEnd());
    }

    @Test
    void repeatedTextIsAmbiguousAndDoesNotGuess() {
        ResolvedEvidence result = grounder.ground("火锅附近再找一家火锅", "火锅");

        assertEquals(ResolvedEvidence.Status.AMBIGUOUS, result.getStatus());
        assertEquals(null, result.getStart());
        assertEquals(null, result.getEnd());
    }

    @Test
    void missingAndUnknownTextRemainUnresolved() {
        assertEquals(ResolvedEvidence.Status.MISSING, grounder.ground("推荐火锅", " ").getStatus());
        assertEquals(ResolvedEvidence.Status.NOT_FOUND, grounder.ground("推荐火锅", "烤肉").getStatus());
    }
}
