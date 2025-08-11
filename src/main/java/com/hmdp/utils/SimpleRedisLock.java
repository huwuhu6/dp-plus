package com.hmdp.utils;


import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 描述：TODO...
 *
 * @author auroraaliu
 * @create 2025-07-18 下午4:47
 */
public class SimpleRedisLock implements  ILock{
    private RedisTemplate redisTemplate;
    private String name;
    public SimpleRedisLock(RedisTemplate redisTemplate, String name){
        this.name=name;
        this.redisTemplate=redisTemplate;
    }
    private String key_prefix="lock:";
    private String threadID_prefix= UUID.randomUUID().toString();
    public Boolean tryLock(long timeoutSec) {
        //获取线程标识
        String threadId=threadID_prefix+Thread.currentThread().getId();
        //获取锁
        Boolean lock=redisTemplate.opsForValue().setIfAbsent(key_prefix+name,threadId,timeoutSec, TimeUnit.SECONDS);
        return lock;
    }

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public void unlock() {
        // 调用lua脚本
        redisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(key_prefix + name),
                threadID_prefix
                        + Thread.currentThread().getId());
    }
}
