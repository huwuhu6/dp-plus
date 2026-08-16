package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.User;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedisTemplate<String, Object> redisTemplate;
    @Resource
    private RedissonClient redissonClient;
    /**
     * 实现下单功能
     * @param voucherId
     * @return 提示词
     */
    @Transactional()
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券
        SeckillVoucher voucher=seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            //尚未开始
            return Result.fail("秒杀尚未开始！");
        }
        //3.判断秒杀是否结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            //尚未开始
            return Result.fail("秒杀已经结束！");
        }
        //4.判断库存是否充足
        if (voucher.getStock()<1){
            return Result.fail("库存不足！");
        }
        //5.使用redis分布式锁实现一人一单
        //5.1创建锁
        Long userId=UserHolder.getUser().getId();
        //SimpleRedisLock simpleRedisLock=new SimpleRedisLock(redisTemplate,"order:"+userId);
        RLock rLock=redissonClient.getLock("lock:order:"+userId);
        //5.2获取锁
        if (!rLock.tryLock()){
            return Result.fail("一人一单，请勿重复购买");
        }
        //5.3释放锁
        try {
            IVoucherOrderService proxy=(IVoucherOrderService) AopContext.currentProxy();
            return proxy.creatVouCherOrder(voucherId);
        }finally {
            rLock.unlock();
        }

    }
    @Transactional
    public Result creatVouCherOrder(Long voucherId){
        //5一人一单逻辑
        //5.1获取订单
        UserDTO user = UserHolder.getUser();
        long count=query().eq("user_id",1010L).eq("voucher_id",voucherId).count();
        //5.2判断是否已经下单过
        if(count>0){
            return Result.fail("用户已经购买过");
        }
        //6.扣减库存
        boolean success= seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id",voucherId)
                .update();
        if(!success){
            return Result.fail("库存不足！");
        }
        //7.创建订单
        VoucherOrder voucherOrder=new VoucherOrder();
        //7.1 订单id
        long orderId=redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //7.2用户id
        Long userId= 1010L;
        voucherOrder.setUserId(userId);
        //7.3代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        //8.返回订单id
        return Result.ok(orderId);
    }
}
/**
 //6.扣减库存
 boolean success=seckillVoucherService.update()
 .setSql("stock=stock-1")
 .eq("voucher_id",voucherId)
 .gt("stock", 0)  // 核心防护
 .update();
 if(!success){
 return Result.fail("库存不足！");
 }
 //7.创建订单
 VoucherOrder voucherOrder=new VoucherOrder();
 //7.1 订单id
 long orderId=redisIdWorker.nextId("order");
 voucherOrder.setId(orderId);
 //7.2用户id
 Long userId= 1010L;
 voucherOrder.setUserId(userId);
 //7.3代金券id
 voucherOrder.setVoucherId(voucherId);
 save(voucherOrder);

 return Result.ok(voucherOrder);
 */
