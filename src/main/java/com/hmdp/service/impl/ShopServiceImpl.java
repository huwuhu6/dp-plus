package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private  StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop =cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        //互斥锁解决缓存击穿
        //Shop shop=queryWithMutex(id);
        //逻辑过期 解决缓存击穿
        Shop shop=cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        if(shop==null){
            return Result.fail("店铺不存在");
        }
        //返回
        return Result.ok(shop);
    }
    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 逻辑过期解决 缓存击穿 根据id查询商铺
     * @param id
     * @return
     */
    public Shop queryWithLogicalExpire(Long id) {
        String key=CACHE_SHOP_KEY+id;
        //1.从redis查询商铺缓存
        String shopJson= stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isBlank(shopJson)){
            //3.不存在，直接返回
            return null;
        }
        //4.命中，需要把json反序列化为对象
        RedisData redisData=JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data=(JSONObject)redisData.getData();
        //把data转为Shop类型
        Shop shop=JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime=redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime!=null && expireTime.isAfter(LocalDateTime.now())){
            //5.1未过期，直接返回店铺信息
            return shop;
        }
        //5.2已过期，需要缓存重建
        //6.缓存重建
        //6.1获取互斥锁
        String lockKey=LOCK_SHOP_KEY+id;
        boolean isLock=tryLock(lockKey);
        //6.2 判断是否获取锁成功
        if (isLock){
            //6.3成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //重建缓存
                    this.saveShop2Redis(id,20L);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //6.4失败，返回过期的商铺信息
        return shop;
    }

    /**
     * 互斥锁 解决 缓存击穿 根据id查询商铺
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        String key=CACHE_SHOP_KEY+id;
        //1.从redis查询商铺缓存
        String shopJson= stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)){
            //3.存在，直接返回
            return JSONUtil.toBean(shopJson,Shop.class);
        }
        //判断控制?
        if (shopJson != null){
            //返回错误信息
            return null;
        }
        //实现缓存重建
        //4.1获取互斥锁
        String lockKey="lock:shop"+id;
        boolean isLock=tryLock(lockKey);
        //4.2判断是否获取成功
        Shop shop=null;
        try {
            if (!isLock) {
                //4.3失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //4.4成功，根据id查询数据库
            shop = getById(id);
            //5.不存在，返回错误
            if (shop == null) {
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6.存在写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        }catch (InterruptedException e){
            throw new RuntimeException(e);
        }finally {
            //7.释放互斥锁
            unlock(lockKey);
        }

        //8.返回
        return shop;
    }


    /**
     * 缓存穿透 根据id查询商铺
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id) {
        String key=CACHE_SHOP_KEY+id;
        //1.从redis查询商铺缓存
        String shopJson= stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)){
            //3.存在，直接返回
            return JSONUtil.toBean(shopJson,Shop.class);
        }
        //判断控制?
        if (shopJson != null){
            //返回错误信息
            return null;
        }

        //4.不存在，根据id查询数据库
        Shop shop= getById(id);
        //5.不存在，返回错误
        if (shop==null){
            stringRedisTemplate.opsForValue().set(key,"", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.存在写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }
    private boolean tryLock(String key){
        Boolean flag=stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    /**
     * 存储店铺信息和逻辑过期时间到redis
     * @param id
     * @param expireSeconds
     */
    public void saveShop2Redis(Long id,Long expireSeconds){
        //1.查询店铺数据
        Shop shop=getById(id);
        //2.封装逻辑过期时间
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusMinutes(expireSeconds));
        //3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));

    }
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id=shop.getId();
        //1.更新数据库
        if (id==null){
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok();
    }
}
