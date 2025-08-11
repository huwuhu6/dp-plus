package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;
    public static final String CACHE_SHOP_TYPE_KEY = "cache:shop_type:";
    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";

    /**
     * 秒杀相关常量
     */
    public static final String SECKILL_REQUEST_QUEUE = "seckill:request:queue:";
    public static final String SECKILL_RESULT_QUEUE = "seckill:result:queue:";
    public static final String SECKILL_LOCK_KEY = "seckill:lock:";
    public static final String SECKILL_USER_ORDER_KEY = "seckill:user:order:";
    
    /**
     * 秒杀配置
     */
    public static final long SECKILL_LOCK_TTL = 10L;
    public static final long SECKILL_REQUEST_TTL = 300L; // 5分钟
    public static final long SECKILL_RESULT_TTL = 600L; // 10分钟

}
