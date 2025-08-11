package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillRequest;
import com.hmdp.service.ISeckillAsyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 测试控制器
 */
@Slf4j
@RestController
@RequestMapping("/test")
public class TestController {

    @Resource
    private ISeckillAsyncService seckillAsyncService;

    /**
     * 并发测试秒杀接口
     */
    @PostMapping("/seckill/concurrent/{voucherId}")
    public Result testConcurrentSeckill(@PathVariable Long voucherId, @RequestParam(defaultValue = "100") Integer threadCount) {
        // 初始化库存
        seckillAsyncService.initSeckillStock(voucherId, 50);
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        for (int i = 0; i < threadCount; i++) {
            final int userId = 1000 + i;
            CompletableFuture.runAsync(() -> {
                try {
                    SeckillRequest request = new SeckillRequest((long) userId, voucherId);
                    Result result = seckillAsyncService.submitSeckillRequest(request);
                    log.info("用户{}提交秒杀请求: {}", userId, result);
                    
                    // 模拟查询结果
                    Thread.sleep(1000);
                    Result queryResult = seckillAsyncService.getSeckillResult(request.getRequestId());
                    log.info("用户{}查询结果: {}", userId, queryResult);
                    
                } catch (Exception e) {
                    log.error("用户{}秒杀异常", userId, e);
                }
            }, executor);
        }
        
        executor.shutdown();
        return Result.ok("并发测试已启动，线程数: " + threadCount);
    }
    
    /**
     * 单次测试秒杀接口
     */
    @PostMapping("/seckill/single/{voucherId}")
    public Result testSingleSeckill(@PathVariable Long voucherId, @RequestParam(defaultValue = "1010") Long userId) {
        // 初始化库存
        seckillAsyncService.initSeckillStock(voucherId, 10);
        
        SeckillRequest request = new SeckillRequest(userId, voucherId);
        Result submitResult = seckillAsyncService.submitSeckillRequest(request);
        
        log.info("提交结果: {}", submitResult);
        
        // 等待处理完成
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        Result queryResult = seckillAsyncService.getSeckillResult(request.getRequestId());
        log.info("查询结果: {}", queryResult);
        
        return Result.ok("测试完成");
    }
} 