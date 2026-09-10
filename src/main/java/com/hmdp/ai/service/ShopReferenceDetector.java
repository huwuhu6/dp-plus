package com.hmdp.ai.service;

import java.util.regex.Pattern;

/** Shared deterministic detection for deictic shop references at the routing boundary. */
public final class ShopReferenceDetector {
    static final Pattern FOCUSED_REFERENCE = Pattern.compile(
            "刚才那家|刚才那个|上一轮那个|上一家|这一家|那一家|这一个|那一个|这家|那家|这个|那个");

    private ShopReferenceDetector() { }

    public static boolean containsFocusedReference(String message) {
        return message != null && FOCUSED_REFERENCE.matcher(message).find();
    }
}
