package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public List<ShopType> TypeList() {
        String key= RedisConstants.CACHE_SHOP_TYPE_KEY;
        //1.从redis中查询类型
        String shopTypeJson=stringRedisTemplate.opsForValue().get(key);
        //2.判断redis中是否存在缓存信息
        if (StrUtil.isNotBlank(shopTypeJson)) {
            //存在直接返回
            List<ShopType> shopTypeList= JSONUtil.toList(shopTypeJson, ShopType.class);
            return shopTypeList;
        }
        //3.不存在 查询数据库
        List<ShopType> typeList=query().orderByAsc("sort").list();
        //4.存入redis中
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList));
        //5.返回从数据库中查询到的类型信息
        return typeList;

    }
}
