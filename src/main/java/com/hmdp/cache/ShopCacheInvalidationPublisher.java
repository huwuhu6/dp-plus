package com.hmdp.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes best-effort L1 invalidation notifications after the shared cache is invalidated.
 */
@Component
public class ShopCacheInvalidationPublisher {
    public static final String CHANNEL = "cache:shop:invalidate";

    private static final Logger log = LoggerFactory.getLogger(ShopCacheInvalidationPublisher.class);

    private final StringRedisTemplate stringRedisTemplate;

    public ShopCacheInvalidationPublisher(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void publish(Long shopId) {
        if (shopId == null) return;
        try {
            stringRedisTemplate.convertAndSend(CHANNEL, shopId.toString());
        } catch (RuntimeException e) {
            // Pub/Sub has no persistence. L1 refresh/expiry remains the convergence fallback.
            log.warn("[CACHE][shop] event=L1_INVALIDATE_PUBLISH_FAILURE shopId={} errorType={}",
                    shopId, e.getClass().getSimpleName());
        }
    }
}
