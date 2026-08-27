package com.hmdp.ai.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves an explicitly requested recommendation count without depending on model rewriting. */
public final class RecommendationCountResolver {
    static final int DEFAULT_COUNT = 3;
    static final int MAX_COUNT = 5;
    private static final Pattern ARABIC_COUNT = Pattern.compile("(?:推荐|来|给我|再)\\s*(\\d+)\\s*家");
    private static final Pattern CHINESE_COUNT = Pattern.compile("(?:推荐|来|给我|再)\\s*([一二两三四五六七八九十])\\s*家");

    public int resolve(String message) {
        String text = message == null ? "" : message.replaceAll("\\s+", "");
        Matcher arabic = ARABIC_COUNT.matcher(text);
        if (arabic.find()) return bounded(Integer.parseInt(arabic.group(1)));
        Matcher chinese = CHINESE_COUNT.matcher(text);
        if (chinese.find()) return bounded(chineseNumber(chinese.group(1)));
        if (text.matches(".*(?:再)?多(?:推荐|来|给)?几家.*")) return MAX_COUNT;
        return DEFAULT_COUNT;
    }

    private int bounded(int count) {
        return Math.max(1, Math.min(count, MAX_COUNT));
    }

    private int chineseNumber(String value) {
        if ("一".equals(value)) return 1;
        if ("二".equals(value) || "两".equals(value)) return 2;
        if ("三".equals(value)) return 3;
        if ("四".equals(value)) return 4;
        if ("五".equals(value)) return 5;
        if ("六".equals(value)) return 6;
        if ("七".equals(value)) return 7;
        if ("八".equals(value)) return 8;
        if ("九".equals(value)) return 9;
        if ("十".equals(value)) return 10;
        return DEFAULT_COUNT;
    }
}
