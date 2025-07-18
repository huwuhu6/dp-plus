package com.hmdp.utils;

import org.springframework.data.redis.core.RedisTemplate;

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
    private String namePrefix="lock:";
    public Boolean tryLock(long timeoutSec) {
        //获取线程标识
        String threadName=Thread.currentThread().getName();
        //获取锁
        Boolean lock=redisTemplate.opsForValue().setIfAbsent(namePrefix+name,threadName,timeoutSec, TimeUnit.SECONDS);
        return lock;
    }

    @Override
    public void unlock() {
        redisTemplate.delete(namePrefix+name);
    }
}
