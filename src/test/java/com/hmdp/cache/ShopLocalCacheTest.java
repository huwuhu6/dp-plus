package com.hmdp.cache;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopLocalCacheTest {
    @Test
    void coalescesOneThousandConcurrentRequestsForTheSameShop() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        CountDownLatch l2ReadStarted = new CountDownLatch(1);
        CountDownLatch allowL2ReadToFinish = new CountDownLatch(1);
        when(valueOperations.get("cache:shop:1")).thenAnswer(invocation -> {
            l2ReadStarted.countDown();
            allowL2ReadToFinish.await(5, TimeUnit.SECONDS);
            return JSONUtil.toJsonStr(shop(1L, "热点商铺"));
        });

        ExecutorService loaderExecutor = Executors.newSingleThreadExecutor();
        ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();
        ShopLocalCache cache = new ShopLocalCache(redisTemplate, mock(ShopMapper.class), properties(), loaderExecutor);
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
            verify(valueOperations, times(1)).get("cache:shop:1");
            assertEquals(1L, cache.stats().l2LoadCount());
            assertEquals(999L, cache.stats().hitCount());
        } finally {
            cache.shutdown();
            callers.shutdownNow();
            loaderExecutor.shutdownNow();
        }
    }

    @Test
    void retriesAfterAnInitialLoadFailureInsteadOfCachingTheFailure() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("cache:shop:1")).thenReturn(null);
        ShopMapper shopMapper = mock(ShopMapper.class);
        when(shopMapper.selectCacheById(anyLong()))
                .thenThrow(new IllegalStateException("database temporarily unavailable"))
                .thenReturn(shop(1L, "恢复后的商铺"));

        ExecutorService loaderExecutor = Executors.newSingleThreadExecutor();
        ShopLocalCache cache = new ShopLocalCache(redisTemplate, shopMapper, properties(), loaderExecutor);
        try {
            assertThrows(IllegalStateException.class, () -> cache.get(1L));
            Shop result = cache.get(1L);
            assertNotNull(result);
            assertEquals("恢复后的商铺", result.getName());
            verify(shopMapper, times(2)).selectCacheById(1L);
            assertEquals(1L, cache.stats().loadFailureCount());
        } finally {
            cache.shutdown();
            loaderExecutor.shutdownNow();
        }
    }

    @Test
    void invalidationMakesTheNextRequestLoadTheUpdatedSharedValue() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("cache:shop:1"))
                .thenReturn(JSONUtil.toJsonStr(shop(1L, "更新前")))
                .thenReturn(JSONUtil.toJsonStr(shop(1L, "更新后")));

        ExecutorService loaderExecutor = Executors.newSingleThreadExecutor();
        ShopLocalCache cache = new ShopLocalCache(redisTemplate, mock(ShopMapper.class), properties(), loaderExecutor);
        try {
            assertEquals("更新前", cache.get(1L).getName());
            assertEquals("更新前", cache.get(1L).getName());
            cache.invalidate(1L);
            assertEquals("更新后", cache.get(1L).getName());
            verify(valueOperations, times(2)).get(eq("cache:shop:1"));
        } finally {
            cache.shutdown();
            loaderExecutor.shutdownNow();
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
}
