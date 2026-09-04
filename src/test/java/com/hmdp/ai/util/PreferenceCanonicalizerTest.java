package com.hmdp.ai.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreferenceCanonicalizerTest {

    @Test
    void mapsKnownSynonymsToCanonicalTags() {
        assertEquals("约会", PreferenceCanonicalizer.canonicalize("情侣"));
        assertEquals("约会", PreferenceCanonicalizer.canonicalize("二人世界"));
        assertEquals("不排队", PreferenceCanonicalizer.canonicalize("不想排队"));
        assertEquals("不排队", PreferenceCanonicalizer.canonicalize("少排队"));
        assertEquals("安静", PreferenceCanonicalizer.canonicalize("静一点"));
        assertEquals("安静", PreferenceCanonicalizer.canonicalize("别太吵"));
        assertEquals("清淡", PreferenceCanonicalizer.canonicalize("少油"));
        assertEquals("清淡", PreferenceCanonicalizer.canonicalize("不油腻"));
    }

    @Test
    void keepsOpenEndedPhrasesVerbatim() {
        assertEquals("辣", PreferenceCanonicalizer.canonicalize("辣"));
        assertEquals("氛围好", PreferenceCanonicalizer.canonicalize("氛围好"));
        assertEquals("性价比高", PreferenceCanonicalizer.canonicalize("性价比高"));
        assertEquals("适合聚餐", PreferenceCanonicalizer.canonicalize("适合聚餐"));
    }

    @Test
    void canonicalLabelItselfPassesThrough() {
        assertEquals("约会", PreferenceCanonicalizer.canonicalize("约会"));
        assertEquals("安静", PreferenceCanonicalizer.canonicalize("安静"));
    }

    @Test
    void trimsAndSkipsBlankInput() {
        assertEquals("安静", PreferenceCanonicalizer.canonicalize("  安静  "));
        assertEquals("", PreferenceCanonicalizer.canonicalize(""));
        assertEquals("", PreferenceCanonicalizer.canonicalize("  "));
        assertEquals("", PreferenceCanonicalizer.canonicalize(null));
    }

    @Test
    void canonicalizeAllDeduplicatesAndPreservesOrder() {
        List<String> result = PreferenceCanonicalizer.canonicalizeAll(
                Arrays.asList("情侣", "约会", "安静", "静一点", "少油", "氛围好"));
        assertEquals(Arrays.asList("约会", "安静", "清淡", "氛围好"), result);
    }

    @Test
    void canonicalizeAllHandlesNullAndEmpty() {
        assertTrue(PreferenceCanonicalizer.canonicalizeAll(null).isEmpty());
        assertTrue(PreferenceCanonicalizer.canonicalizeAll(Collections.emptyList()).isEmpty());
        assertEquals(Collections.singletonList("安静"), PreferenceCanonicalizer.canonicalizeAll(Arrays.asList(" ", "安静")));
    }
}
