package com.hmdp.ai.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Canonicalizes open-set preference phrases to shared canonical tags so that
 * the deterministic consumers (scoring bonuses, evidence requirements, relaxation
 * options) can match on a fixed label set instead of raw LLM output.
 *
 * <p>Design principles (aligned with {@link CuisineCanonicalizer}):
 * <ul>
 *   <li>Closed set of explicit synonym maps for <b>judgeable</b> preferences only
 *       (约会/安静/不排队/清淡) — the ones the scoring rules actually consume.</li>
 *   <li>Phrases not mapped to any canonical tag are kept verbatim (open-ended
 *       semantic-channel fallback) — never dropped, never forced into a wrong bucket.</li>
 *   <li>The same canonicalization must run on the producing side <b>before the state
 *       is written</b> so consumers can rely on canonical labels (Consumer-Side
 *       Contract). Negation ("不要安静") is handled by the merge rule layer, not here.</li>
 * </ul>
 */
public final class PreferenceCanonicalizer {

    private PreferenceCanonicalizer() {
    }

    /** Canonical tag -> synonym phrases. The canonical label itself is listed first. */
    private static final Map<String, String[]> SYNONYMS = buildSynonyms();

    private static Map<String, String[]> buildSynonyms() {
        Map<String, String[]> map = new LinkedHashMap<>();
        map.put("约会", new String[]{"约会", "情侣", "女朋友", "男朋友", "浪漫", "二人世界"});
        map.put("不排队", new String[]{"不排队", "不想排队", "不用排队", "少排队", "避免排队", "别排队"});
        map.put("安静", new String[]{"安静", "静一点", "别太吵", "清净", "清静", "安静些"});
        map.put("清淡", new String[]{"清淡", "少油", "不油腻", "清爽", "清淡口味"});
        return map;
    }

    /** Maps a single raw phrase to its canonical tag, or keeps it verbatim if unmapped. */
    public static String canonicalize(String raw) {
        if (raw == null) {
            return "";
        }
        String tag = raw.trim();
        if (tag.isEmpty()) {
            return "";
        }
        for (Map.Entry<String, String[]> entry : SYNONYMS.entrySet()) {
            for (String synonym : entry.getValue()) {
                if (tag.contains(synonym)) {
                    return entry.getKey();
                }
            }
        }
        return tag;
    }

    /**
     * Maps a list of raw phrases, preserving order, trimming, and deduplicating.
     * Blank entries are skipped.
     */
    public static List<String> canonicalizeAll(List<String> raws) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (raws != null) {
            for (String raw : raws) {
                String canonical = canonicalize(raw);
                if (!canonical.isEmpty()) {
                    result.add(canonical);
                }
            }
        }
        return new ArrayList<>(result);
    }
}
