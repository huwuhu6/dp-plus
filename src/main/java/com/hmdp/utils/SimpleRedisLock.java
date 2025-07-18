package com.hmdp.utils;

import org.springframework.data.redis.core.RedisTemplate;

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

    @Override
    public void unlock() {
        //获取线程标识
        String threadId=threadID_prefix+Thread.currentThread().getId();
        //获取锁中的标识
        String id= (String) redisTemplate.opsForValue().get(key_prefix+name);
        if (id.equals(threadId)){
            redisTemplate.delete(key_prefix+name);
        }

    }
}
