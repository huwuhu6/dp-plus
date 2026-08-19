package com.hmdp.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Each JVM removes only its own Caffeine entry when it receives an invalidation notification.
 */
@Component
public class ShopCacheInvalidationListener implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(ShopCacheInvalidationListener.class);

    private final ShopLocalCache shopLocalCache;

    public ShopCacheInvalidationListener(ShopLocalCache shopLocalCache) {
        this.shopLocalCache = shopLocalCache;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            long shopId = Long.parseLong(payload);
            if (shopId <= 0) throw new NumberFormatException("shopId must be positive");
            shopLocalCache.invalidate(shopId);
            log.debug("[CACHE][shop] event=L1_INVALIDATED_BY_BROADCAST shopId={}", shopId);
        } catch (NumberFormatException e) {
            log.warn("[CACHE][shop] event=L1_INVALIDATE_MESSAGE_IGNORED payload={}", payload);
        } catch (RuntimeException e) {
            log.warn("[CACHE][shop] event=L1_INVALIDATE_MESSAGE_FAILURE errorType={}", e.getClass().getSimpleName());
        }
    }
}
