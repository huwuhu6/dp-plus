package com.hmdp.cache;

public record ShopCacheStats(
        boolean enabled,
        long hitCount,
        long missCount,
        long loadSuccessCount,
        long loadFailureCount,
        long l2LoadCount,
        long databaseLoadCount,
        double hitRate
) {
}
