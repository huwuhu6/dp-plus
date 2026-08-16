package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillRequest;
import com.hmdp.service.ISeckillAsyncService;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;
    
    @Resource
    private ISeckillAsyncService seckillAsyncService;

    @PostMapping("/seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }
    
    /**
     * 异步秒杀接口
     */
    @PostMapping("/seckill/async/{id}")
    public Result asyncSeckillVoucher(@PathVariable("id") Long voucherId) {
        // 为了测试，使用固定用户ID
        Long userId = 1010L;
        SeckillRequest request = new SeckillRequest(userId, voucherId);
        return seckillAsyncService.submitSeckillRequest(request);
    }
    
    /**
     * 查询秒杀结果
     */
    @GetMapping("/seckill/result/{requestId}")
    public Result getSeckillResult(@PathVariable("requestId") String requestId) {
        return seckillAsyncService.getSeckillResult(requestId);
    }
    
    /**
     * 初始化秒杀库存（管理员接口）
     */
    @PostMapping("/seckill/init/{id}")
    public Result initSeckillStock(@PathVariable("id") Long voucherId, @RequestParam Integer stock) {
        seckillAsyncService.initSeckillStock(voucherId, stock);
        return Result.ok("库存初始化成功");
    }
}
