package com.hmdp.cache;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import org.springframework.data.redis.connection.DefaultMessage;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShopLocalCacheTest {
    @Test
    void coalescesOneThousandConcurrentRequestsForTheSameShop() throws Exception {
        CountDownLatch l2ReadStarted = new CountDownLatch(1);
        CountDownLatch allowL2ReadToFinish = new CountDownLatch(1);
        FakeStringRedisTemplate redisTemplate = new FakeStringRedisTemplate((key, callNumber) -> {
            l2ReadStarted.countDown();
            allowL2ReadToFinish.await(5, TimeUnit.SECONDS);
            return JSONUtil.toJsonStr(shop(1L, "热点商铺"));
        });

        ExecutorService loaderExecutor = Executors.newSingleThreadExecutor();
        ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();
        ShopLocalCache cache = new ShopLocalCache(redisTemplate, mapperReturning(null), properties(), loaderExecutor);
        try {
            CountDownLatch ready = new CountDownLatch(1_000);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Shop>> results = new ArrayList<>();
            for (int i = 0; i < 1_000; i++) {
                results.add(callers.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return cache.get(1L);
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            l2ReadStarted.await(5, TimeUnit.SECONDS);
            allowL2ReadToFinish.countDown();

            for (Future<Shop> result : results) assertEquals("热点商铺", result.get(5, TimeUnit.SECONDS).getName());
            assertEquals(1, redisTemplate.getCount("cache:shop:1"));
            assertEquals(1L, cache.stats().l2LoadCount());
            assertEquals(999L, cache.stats().hitCount());
        } finally {
            cache.shutdown();
            callers.shutdownNow();
            shutdownExecutor(loaderExecutor);
        }
    }

    @Test
    void retriesAfterAnInitialLoadFailureInsteadOfCachingTheFailure() {
        FakeStringRedisTemplate redisTemplate = new FakeStringRedisTemplate((key, callNumber) -> null);
        AtomicInteger mapperCalls = new AtomicInteger();
        ShopMapper shopMapper = mapperAnswering(id -> {
            if (mapperCalls.incrementAndGet() == 1) {
                throw new IllegalStateException("database temporarily unavailable");
            }
            return shop(1L, "恢复后的商铺");
        });

        ShopLocalCache cache = new ShopLocalCache(redisTemplate, shopMapper, properties(), directExecutor());
        try {
            assertThrows(IllegalStateException.class, () -> cache.get(1L));
            Shop result = cache.get(1L);
            assertNotNull(result);
            assertEquals("恢复后的商铺", result.getName());
            assertEquals(2, mapperCalls.get());
            assertEquals(1L, cache.stats().loadFailureCount());
        } finally {
            cache.shutdown();
        }
    }

    @Test
    void invalidationMakesTheNextRequestLoadTheUpdatedSharedValue() {
        FakeStringRedisTemplate redisTemplate = new FakeStringRedisTemplate((key, callNumber) -> {
            if (callNumber == 1) return JSONUtil.toJsonStr(shop(1L, "更新前"));
            return JSONUtil.toJsonStr(shop(1L, "更新后"));
        });

        ShopLocalCache cache = new ShopLocalCache(redisTemplate, mapperReturning(null), properties(), directExecutor());
        try {
            assertEquals("更新前", cache.get(1L).getName());
            assertEquals("更新前", cache.get(1L).getName());
            cache.invalidate(1L);
            assertEquals("更新后", cache.get(1L).getName());
            assertEquals(2, redisTemplate.getCount("cache:shop:1"));
        } finally {
            cache.shutdown();
        }
    }

    @Test
    void broadcastInvalidationEvictsOnlyTheReceivingJvmLocalEntry() {
        FakeStringRedisTemplate redisTemplate = new FakeStringRedisTemplate((key, callNumber) -> {
            if (callNumber == 1) return JSONUtil.toJsonStr(shop(1L, "广播前"));
            return JSONUtil.toJsonStr(shop(1L, "广播后"));
        });
        ShopLocalCache cache = new ShopLocalCache(redisTemplate, mapperReturning(null), properties(), directExecutor());
        ShopCacheInvalidationListener listener = new ShopCacheInvalidationListener(cache);
        try {
            assertEquals("广播前", cache.get(1L).getName());
            listener.onMessage(message("1"), null);
            assertEquals("广播后", cache.get(1L).getName());
            assertEquals(2, redisTemplate.getCount("cache:shop:1"));

            listener.onMessage(message("not-a-shop-id"), null);
            assertEquals("广播后", cache.get(1L).getName());
            assertEquals(2, redisTemplate.getCount("cache:shop:1"));
        } finally {
            cache.shutdown();
        }
    }

    @Test
    void separateJvmLocalCachesEachLoadTheSameKeyOnce() {
        FakeStringRedisTemplate redisTemplate = new FakeStringRedisTemplate(
                (key, callNumber) -> JSONUtil.toJsonStr(shop(1L, "双实例热点商铺")));

        ShopLocalCache firstInstance = new ShopLocalCache(redisTemplate, mapperReturning(null), properties(), directExecutor());
        ShopLocalCache secondInstance = new ShopLocalCache(redisTemplate, mapperReturning(null), properties(), directExecutor());
        try {
            assertEquals("双实例热点商铺", firstInstance.get(1L).getName());
            assertEquals("双实例热点商铺", secondInstance.get(1L).getName());
            assertEquals(1L, firstInstance.stats().l2LoadCount());
            assertEquals(1L, secondInstance.stats().l2LoadCount());
            assertEquals(2, redisTemplate.getCount("cache:shop:1"));
        } finally {
            firstInstance.shutdown();
            secondInstance.shutdown();
        }
    }

    private ShopCacheProperties properties() {
        ShopCacheProperties properties = new ShopCacheProperties();
        properties.setEnabled(true);
        properties.setMaximumSize(100);
        properties.setRefreshAfterWriteSeconds(5);
        properties.setExpireAfterWriteSeconds(30);
        return properties;
    }

    private Shop shop(Long id, String name) {
        return new Shop().setId(id).setName(name);
    }

    private Executor directExecutor() {
        return Runnable::run;
    }

    private DefaultMessage message(String payload) {
        return new DefaultMessage(ShopCacheInvalidationPublisher.CHANNEL.getBytes(StandardCharsets.UTF_8),
                payload.getBytes(StandardCharsets.UTF_8));
    }

    private void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private ShopMapper mapperReturning(Shop shop) {
        return mapperAnswering(id -> shop);
    }

    private ShopMapper mapperAnswering(ShopSelectHandler handler) {
        return (ShopMapper) Proxy.newProxyInstance(ShopMapper.class.getClassLoader(), new Class<?>[]{ShopMapper.class},
                (proxy, method, args) -> {
                    if ("selectCacheById".equals(method.getName())) return handler.select((Long) args[0]);
                    if ("toString".equals(method.getName())) return "FakeShopMapper";
                    throw new UnsupportedOperationException("Unsupported mapper method: " + method.getName());
                });
    }

    @FunctionalInterface
    private interface ShopSelectHandler {
        Shop select(Long id);
    }

    @FunctionalInterface
    private interface RedisGetHandler {
        String get(String key, int callNumber) throws Exception;
    }

    private static class FakeStringRedisTemplate extends StringRedisTemplate {
        private final RedisGetHandler getHandler;
        private final Map<String, AtomicInteger> getCounts = new ConcurrentHashMap<>();
        private final Map<String, String> values = new ConcurrentHashMap<>();
        private final ValueOperations<String, String> valueOperations;

        FakeStringRedisTemplate(RedisGetHandler getHandler) {
            this.getHandler = getHandler;
            this.valueOperations = fakeValueOperations();
        }

        @Override
        public ValueOperations<String, String> opsForValue() {
            return valueOperations;
        }

        int getCount(String key) {
            AtomicInteger count = getCounts.get(key);
            return count == null ? 0 : count.get();
        }

        @SuppressWarnings("unchecked")
        private ValueOperations<String, String> fakeValueOperations() {
            return (ValueOperations<String, String>) Proxy.newProxyInstance(
                    ValueOperations.class.getClassLoader(),
                    new Class<?>[]{ValueOperations.class},
                    (proxy, method, args) -> {
                        if ("get".equals(method.getName())) {
                            String key = (String) args[0];
                            int callNumber = getCounts.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
                            String value = getHandler.get(key, callNumber);
                            return value == null ? values.get(key) : value;
                        }
                        if ("set".equals(method.getName())) {
                            values.put((String) args[0], (String) args[1]);
                            return null;
                        }
                        if ("toString".equals(method.getName())) return "FakeValueOperations";
                        throw new UnsupportedOperationException("Unsupported Redis operation: " + method.getName());
                    });
        }
    }
}
