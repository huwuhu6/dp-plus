package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.VoucherPackageOrder;
import org.apache.ibatis.annotations.Select;

public interface VoucherPackageOrderMapper extends BaseMapper<VoucherPackageOrder> {
    @Select("""
            SELECT id, user_id AS userId, voucher_id AS voucherId, shop_id AS shopId, quantity, paid_amount AS paidAmount,
                   status, create_time AS createTime, update_time AS updateTime
            FROM tb_voucher_package_order
            WHERE id = #{id}
            FOR UPDATE
            """)
    VoucherPackageOrder selectByIdForUpdate(Long id);
}
