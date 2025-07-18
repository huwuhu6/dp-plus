package com.hmdp.utils;

/**
 * 描述：分布式锁的接口
 *
 * @author auroraaliu
 * @create 2025-07-18 下午4:43
 */
public interface ILock {
    /**
     * 获取锁，非阻塞式，失败则放弃
     * @param timeoutSec 超时时间，超时自动释放
     * @return 是否加锁成功
     */
    Boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
