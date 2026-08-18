package com.hmdp.mapper;

import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface ShopMapper extends BaseMapper<Shop> {

    /**
     * The legacy demo schema does not contain the later geography columns.
     * Shop-detail caching only needs the stable base columns below.
     */
    @Select("SELECT id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours, "
            + "create_time, update_time FROM tb_shop WHERE id = #{id}")
    Shop selectCacheById(Long id);
}
