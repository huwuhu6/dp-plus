package com.hmdp.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillRequest;
import com.hmdp.dto.SeckillResult;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillAsyncService;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀异步服务实现类
 */
@Slf4j
@Service
public class SeckillAsyncServiceImpl implements ISeckillAsyncService {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;
    
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    
    @Resource
    private IVoucherOrderService voucherOrderService;
    
    @Resource
    private RedisIdWorker redisIdWorker;
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Result submitSeckillRequest(SeckillRequest request) {
        try {
            // 1. 检查秒杀是否开始
            SeckillVoucher voucher = seckillVoucherService.getById(request.getVoucherId());
            if (voucher == null) {
                return Result.fail("优惠券不存在");
            }
            
            if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
                return Result.fail("秒杀尚未开始");
            }
            
            if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
                return Result.fail("秒杀已经结束");
            }
            
            // 2. 检查用户是否已经购买过
            String userOrderKey = RedisConstants.SECKILL_USER_ORDER_KEY + request.getVoucherId() + ":" + request.getUserId();
            Boolean hasOrdered = redisTemplate.hasKey(userOrderKey);
            if (Boolean.TRUE.equals(hasOrdered)) {
                return Result.fail("您已经购买过该优惠券");
            }
            
            // 3. 检查Redis中的库存
            String stockKey = RedisConstants.SECKILL_STOCK_KEY + request.getVoucherId();
            Integer stock = (Integer) redisTemplate.opsForValue().get(stockKey);
            if (stock != null && stock <= 0) {
                return Result.fail("库存不足");
            }
            
            // 4. 将请求放入队列
            String queueKey = RedisConstants.SECKILL_REQUEST_QUEUE + request.getVoucherId();
            String requestJson = objectMapper.writeValueAsString(request);
            redisTemplate.opsForList().rightPush(queueKey, requestJson);
            redisTemplate.expire(queueKey, RedisConstants.SECKILL_REQUEST_TTL, TimeUnit.SECONDS);
            
            // 5. 预扣减Redis库存（乐观锁）
            if (stock != null) {
                redisTemplate.opsForValue().decrement(stockKey);
            }
            
            log.info("秒杀请求已提交到队列: {}", request.getRequestId());
            return Result.ok("请求已提交，请稍后查询结果");
            
        } catch (JsonProcessingException e) {
            log.error("序列化秒杀请求失败", e);
            return Result.fail("系统异常");
        }
    }

    @Override
    public void processSeckillRequests(Long voucherId) {
        String queueKey = RedisConstants.SECKILL_REQUEST_QUEUE + voucherId;
        String lockKey = RedisConstants.SECKILL_LOCK_KEY + voucherId;
        
        // 使用分布式锁确保同一时间只有一个线程处理
        SimpleRedisLock lock = new SimpleRedisLock(redisTemplate, lockKey);
        if (!lock.tryLock(RedisConstants.SECKILL_LOCK_TTL)) {
            log.info("获取处理锁失败，voucherId: {}", voucherId);
            return;
        }
        
        try {
            // 批量获取请求（最多100个）
           // List<Object> requests = redisTemplate.opsForList().leftPop(queueKey, 100);
            // 批量弹出（最多100个）
            List<Object> requests = redisTemplate.opsForList().range(queueKey, 0, 99); // 获取前100个
            redisTemplate.opsForList().trim(queueKey, 100, -1); // 删除已获取的元素
            if (requests == null || requests.isEmpty()) {
                return;
            }
            
            log.info("开始批量处理秒杀请求，voucherId: {}, 请求数量: {}", voucherId, requests.size());
            
            // 解析请求
            List<SeckillRequest> seckillRequests = new ArrayList<>();
            for (Object requestObj : requests) {
                try {
                    SeckillRequest request = objectMapper.readValue(requestObj.toString(), SeckillRequest.class);
                    seckillRequests.add(request);
                } catch (Exception e) {
                    log.error("解析秒杀请求失败: {}", requestObj, e);
                }
            }
            
            // 批量处理
            batchProcessSeckillRequests(voucherId, seckillRequests);
            
        } catch (Exception e) {
            log.error("批量处理秒杀请求失败，voucherId: {}", voucherId, e);
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 批量处理秒杀请求
     */
    private void batchProcessSeckillRequests(Long voucherId, List<SeckillRequest> requests) {
        if (requests.isEmpty()) {
            return;
        }
        
        // 1. 查询数据库中的实际库存
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null || voucher.getStock() <= 0) {
            // 库存不足，所有请求都失败
            for (SeckillRequest request : requests) {
                SeckillResult result = new SeckillResult(request.getRequestId(), request.getUserId(), request.getVoucherId(), false);
                result.setErrorMessage("库存不足");
                saveSeckillResult(result);
            }
            return;
        }
        
        // 2. 计算可处理的请求数量
        int availableStock = Math.min(voucher.getStock(), requests.size());
        int successCount = 0;
        
        // 3. 批量创建订单
        List<VoucherOrder> orders = new ArrayList<>();
        for (int i = 0; i < availableStock; i++) {
            SeckillRequest request = requests.get(i);
            
            // 检查用户是否重复购买
            String userOrderKey = RedisConstants.SECKILL_USER_ORDER_KEY + voucherId + ":" + request.getUserId();
            Boolean hasOrdered = redisTemplate.hasKey(userOrderKey);
            if (Boolean.TRUE.equals(hasOrdered)) {
                SeckillResult result = new SeckillResult(request.getRequestId(), request.getUserId(), request.getVoucherId(), false);
                result.setErrorMessage("您已经购买过该优惠券");
                saveSeckillResult(result);
                continue;
            }
            
            // 创建订单
            VoucherOrder order = new VoucherOrder();
            order.setId(redisIdWorker.nextId("order"));
            order.setUserId(request.getUserId());
            order.setVoucherId(voucherId);
            order.setStatus(1); // 未支付
            order.setCreateTime(LocalDateTime.now());
            order.setUpdateTime(LocalDateTime.now());
            
            orders.add(order);
            successCount++;
            
            // 标记用户已购买
            redisTemplate.opsForValue().set(userOrderKey, order.getId(), RedisConstants.SECKILL_RESULT_TTL, TimeUnit.SECONDS);
            
            // 保存成功结果
            SeckillResult result = new SeckillResult(request.getRequestId(), request.getUserId(), request.getVoucherId(), true);
            result.setOrderId(order.getId());
            saveSeckillResult(result);
        }
        
        // 4. 批量保存订单
        if (!orders.isEmpty()) {
            voucherOrderService.saveBatch(orders);
        }
        
        // 5. 批量扣减库存
        if (successCount > 0) {
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - " + successCount)
                    .eq("voucher_id", voucherId)
                    .ge("stock", successCount)
                    .update();
            
            if (!success) {
                log.error("批量扣减库存失败，voucherId: {}, 扣减数量: {}", voucherId, successCount);
            }
        }
        
        // 6. 处理失败的请求
        for (int i = successCount; i < requests.size(); i++) {
            SeckillRequest request = requests.get(i);
            SeckillResult result = new SeckillResult(request.getRequestId(), request.getUserId(), request.getVoucherId(), false);
            result.setErrorMessage("库存不足");
            saveSeckillResult(result);
        }
        
        log.info("批量处理完成，voucherId: {}, 成功: {}, 失败: {}", voucherId, successCount, requests.size() - successCount);
    }

    @Override
    public Result getSeckillResult(String requestId) {
        String resultKey = RedisConstants.SECKILL_RESULT_QUEUE + requestId;
        Object resultObj = redisTemplate.opsForValue().get(resultKey);
        
        if (resultObj == null) {
            return Result.fail("结果不存在或已过期");
        }
        
        try {
            SeckillResult result = objectMapper.readValue(resultObj.toString(), SeckillResult.class);
            if (result.getSuccess()) {
                return Result.ok(result.getOrderId());
            } else {
                return Result.fail(result.getErrorMessage());
            }
        } catch (Exception e) {
            log.error("解析秒杀结果失败", e);
            return Result.fail("系统异常");
        }
    }

    @Override
    public void initSeckillStock(Long voucherId, Integer stock) {
        String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucherId;
        redisTemplate.opsForValue().set(stockKey, stock, RedisConstants.SECKILL_REQUEST_TTL, TimeUnit.SECONDS);
        log.info("初始化秒杀库存，voucherId: {}, stock: {}", voucherId, stock);
    }
    
    /**
     * 保存秒杀结果到Redis
     */
    private void saveSeckillResult(SeckillResult result) {
        try {
            String resultKey = RedisConstants.SECKILL_RESULT_QUEUE + result.getRequestId();
            String resultJson = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(resultKey, resultJson, RedisConstants.SECKILL_RESULT_TTL, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            log.error("保存秒杀结果失败", e);
        }
    }
    
    /**
     * 定时任务：每100ms处理一次秒杀请求
     */
    @Scheduled(fixedRate = 100)
    public void scheduledProcessSeckillRequests() {
        // 这里可以通过配置或数据库查询获取所有活跃的秒杀活动
        // 为了简化，这里处理固定的优惠券ID
        processSeckillRequests(1L); // 假设优惠券ID为1
    }
} 