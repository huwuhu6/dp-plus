package com.hmdp.ai.service;

import com.hmdp.ai.dto.ReferenceIntent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReferenceIntentExtractorTest {
    private final ReferenceIntentExtractor extractor = new ReferenceIntentExtractor();

    @Test
    void extractsMultipleReferencesWithSpans() {
        List<ReferenceIntent> intents = extractor.extract("第一家太贵了，第二家有插座吗？");
        assertEquals(2, intents.size());
        assertEquals(1, intents.get(0).getOrdinal());
        assertEquals(2, intents.get(1).getOrdinal());
        assertEquals("第一家", intents.get(0).getSurface());
        assertEquals("第二家", intents.get(1).getSurface());
        assertEquals(0, intents.get(0).getStart());
        assertEquals(7, intents.get(1).getStart());
    }

    @Test
    void distinguishesEarliestAndFocusedScopes() {
        List<ReferenceIntent> intents = extractor.extract("最开始第一家和刚才那家");
        assertEquals(2, intents.size());
        assertEquals(ReferenceIntent.Scope.EARLIEST, intents.get(0).getScope());
        assertEquals(ReferenceIntent.Scope.FOCUSED, intents.get(1).getScope());
    }
}
