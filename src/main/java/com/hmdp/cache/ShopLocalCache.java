package com.hmdp.cache;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.utils.RedisConstants;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * L1 only protects this JVM. Redis and MySQL remain the shared cache and source of truth.
 */
@Component
public class ShopLocalCache {
    private static final Logger log = LoggerFactory.getLogger(ShopLocalCache.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final ShopMapper shopMapper;
    private final ShopCacheProperties properties;
    private final Executor refreshExecutor;
    private final ExecutorService ownedRefreshExecutor;
    private final AtomicLong l2LoadCount = new AtomicLong();
    private final AtomicLong databaseLoadCount = new AtomicLong();
    private volatile AsyncLoadingCache<Long, ShopCacheValue> cache;

    @Autowired
    public ShopLocalCache(StringRedisTemplate stringRedisTemplate, ShopMapper shopMapper, ShopCacheProperties properties) {
        this(stringRedisTemplate, shopMapper, properties, newRefreshExecutor(), true);
    }

    ShopLocalCache(StringRedisTemplate stringRedisTemplate, ShopMapper shopMapper, ShopCacheProperties properties,
                   Executor refreshExecutor) {
        this(stringRedisTemplate, shopMapper, properties, refreshExecutor, false);
    }

    private ShopLocalCache(StringRedisTemplate stringRedisTemplate, ShopMapper shopMapper, ShopCacheProperties properties,
                           Executor refreshExecutor, boolean ownsRefreshExecutor) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.shopMapper = shopMapper;
        this.properties = properties;
        this.refreshExecutor = refreshExecutor;
        this.ownedRefreshExecutor = ownsRefreshExecutor ? (ExecutorService) refreshExecutor : null;
        this.cache = newCache();
    }

    public Shop get(Long shopId) {
        if (shopId == null) return null;
        if (!properties.isEnabled()) return loadFromSharedCache(shopId).shop();
        CompletableFuture<ShopCacheValue> future = cache.get(shopId);
        try {
            return future.join().shop();
        } catch (CompletionException e) {
            // Do not let a transient first-load failure poison immediate client retries.
            cache.asMap().remove(shopId, future);
            throw new IllegalStateException("查询商铺缓存失败", e.getCause());
        }
    }

    public void invalidate(Long shopId) {
        if (shopId != null) cache.synchronous().invalidate(shopId);
    }

    public ShopCacheStats stats() {
        CacheStats stats = cache.synchronous().stats();
        return new ShopCacheStats(properties.isEnabled(), stats.hitCount(), stats.missCount(), stats.loadSuccessCount(),
                stats.loadFailureCount(), l2LoadCount.get(), databaseLoadCount.get(), stats.hitRate());
    }

    private AsyncLoadingCache<Long, ShopCacheValue> newCache() {
        long refreshSeconds = properties.getRefreshAfterWriteSeconds();
        long expireSeconds = properties.getExpireAfterWriteSeconds();
        if (refreshSeconds <= 0 || expireSeconds <= refreshSeconds || properties.getMaximumSize() <= 0) {
            throw new IllegalArgumentException("shop.cache.l1 配置必须满足 maximum-size > 0 且 0 < refresh-after-write-seconds < expire-after-write-seconds");
        }
        return Caffeine.newBuilder()
                .maximumSize(properties.getMaximumSize())
                .refreshAfterWrite(Duration.ofSeconds(refreshSeconds))
                .expireAfterWrite(Duration.ofSeconds(expireSeconds))
                .executor(refreshExecutor)
                .recordStats()
                .buildAsync((shopId, executor) -> CompletableFuture.supplyAsync(() -> loadFromSharedCache(shopId), executor));
    }

    private ShopCacheValue loadFromSharedCache(Long shopId) {
        l2LoadCount.incrementAndGet();
        String key = RedisConstants.CACHE_SHOP_KEY + shopId;
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) return new ShopCacheValue(JSONUtil.toBean(json, Shop.class));
            if (json != null) return ShopCacheValue.NOT_FOUND;
        } catch (RuntimeException e) {
            log.warn("[CACHE][shop] event=L2_READ_FAILURE shopId={} errorType={}", shopId, e.getClass().getSimpleName());
        }

        databaseLoadCount.incrementAndGet();
        Shop shop = shopMapper.selectCacheById(shopId);
        try {
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return ShopCacheValue.NOT_FOUND;
            }
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (RuntimeException e) {
            log.warn("[CACHE][shop] event=L2_WRITE_FAILURE shopId={} errorType={}", shopId, e.getClass().getSimpleName());
        }
        return new ShopCacheValue(shop);
    }

    private static ExecutorService newRefreshExecutor() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "shop-l1-refresh");
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(100), threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @PreDestroy
    void shutdown() {
        if (ownedRefreshExecutor != null) ownedRefreshExecutor.shutdown();
    }

    private record ShopCacheValue(Shop shop) {
        private static final ShopCacheValue NOT_FOUND = new ShopCacheValue(null);
    }
}
