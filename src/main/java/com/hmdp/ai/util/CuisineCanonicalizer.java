package com.hmdp.ai.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Canonicalizes cuisine expressions to a shared canonical value so that
 * user-side and merchant-side expressions map to the same key.
 * <p>
 * Design principles:
 * <ul>
 *   <li>Closed set of explicit mappings — no ontology, no NER, no external knowledge base.</li>
 *   <li>Exact match only — no substring / contains-based fallback in the canonicalizer itself.</li>
 *   <li>Both sides (user query and merchant profile) use the same canonicalization.</li>
 *   <li>After canonicalization, comparison is {@link String#equals(Object)}.</li>
 * </ul>
 * <p>
 * Merchant profile cuisine is a comma-separated string (e.g. {@code "日料,寿司"} or
 * {@code "港式茶餐厅"}). The caller is responsible for splitting by comma and
 * canonicalizing each token individually before comparing.
 */
public class CuisineCanonicalizer {

    private static final Map<String, String> CANONICAL_MAP = new LinkedHashMap<>();

    static {
        // --- Japanese ---
        CANONICAL_MAP.put("日料", "日料");
        CANONICAL_MAP.put("日本料理", "日料");
        CANONICAL_MAP.put("日式料理", "日料");
        CANONICAL_MAP.put("日本菜", "日料");
        CANONICAL_MAP.put("寿司", "日料");

        // --- BBQ / Grill ---
        CANONICAL_MAP.put("烧烤", "烧烤");
        CANONICAL_MAP.put("烤肉", "烧烤");

        // --- Western ---
        CANONICAL_MAP.put("西餐", "西餐");
        CANONICAL_MAP.put("牛排", "西餐");

        // --- Hong Kong style ---
        CANONICAL_MAP.put("港式", "港式");
        CANONICAL_MAP.put("茶餐厅", "港式");
        CANONICAL_MAP.put("港式茶餐厅", "港式");

        // --- Hot pot ---
        CANONICAL_MAP.put("火锅", "火锅");
    }

    private CuisineCanonicalizer() {
        // utility class
    }

    /**
     * Returns the canonical form of {@code cuisine}, or the input itself if no
     * mapping exists. Never returns null.
     *
     * @param cuisine a cuisine expression (may be null, empty, or whitespace)
     * @return the canonical form, or the trimmed input if unmapped
     */
    public static String canonicalize(String cuisine) {
        if (cuisine == null || cuisine.isBlank()) {
            return "";
        }
        String trimmed = cuisine.trim();
        String canonical = CANONICAL_MAP.get(trimmed);
        return canonical != null ? canonical : trimmed;
    }

    /**
     * Returns the set of canonical cuisine values known to this canonicalizer.
     * Useful for diagnostics and testing.
     */
    public static java.util.Set<String> knownCanonicalValues() {
        return new java.util.LinkedHashSet<>(CANONICAL_MAP.values());
    }
}