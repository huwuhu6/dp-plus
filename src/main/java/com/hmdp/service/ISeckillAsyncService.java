package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillRequest;

/**
 * 秒杀异步服务接口
 */
public interface ISeckillAsyncService {
    
    /**
     * 提交秒杀请求到队列
     * @param request 秒杀请求
     * @return 处理结果
     */
    Result submitSeckillRequest(SeckillRequest request);
    
    /**
     * 批量处理秒杀请求
     * @param voucherId 优惠券ID
     */
    void processSeckillRequests(Long voucherId);
    
    /**
     * 查询秒杀结果
     * @param requestId 请求ID
     * @return 处理结果
     */
    Result getSeckillResult(String requestId);
    
    /**
     * 初始化秒杀库存到Redis
     * @param voucherId 优惠券ID
     * @param stock 库存数量
     */
    void initSeckillStock(Long voucherId, Integer stock);
} 