package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.VoucherPackageOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface VoucherPackageOrderMapper extends BaseMapper<VoucherPackageOrder> {
    @Update("""
            UPDATE tb_voucher_package_order
            SET status = CASE
                    WHEN used_count + 1 = quantity THEN 'USED'
                    WHEN used_count + 1 > 0 THEN 'PARTIALLY_USED'
                    WHEN refunded_count = quantity THEN 'REFUNDED'
                    WHEN refunded_count > 0 THEN 'PARTIALLY_REFUNDED'
                    ELSE 'PAID'
                END,
                used_count = used_count + 1,
                update_time = #{now}
            WHERE id = #{orderId}
            """)
    int incrementUsedAndRefreshStatus(@Param("orderId") Long orderId, @Param("now") java.time.LocalDateTime now);

    @Update("""
            UPDATE tb_voucher_package_order
            SET status = CASE
                    WHEN used_count = quantity THEN 'USED'
                    WHEN used_count > 0 THEN 'PARTIALLY_USED'
                    WHEN refunded_count + 1 = quantity THEN 'REFUNDED'
                    WHEN refunded_count + 1 > 0 THEN 'PARTIALLY_REFUNDED'
                    ELSE 'PAID'
                END,
                refunded_count = refunded_count + 1,
                update_time = #{now}
            WHERE id = #{orderId}
            """)
    int incrementRefundedAndRefreshStatus(@Param("orderId") Long orderId, @Param("now") java.time.LocalDateTime now);

    @Update("""
            UPDATE tb_voucher_package_order
            SET status = CASE
                    WHEN used_count - 1 = quantity THEN 'USED'
                    WHEN used_count - 1 > 0 THEN 'PARTIALLY_USED'
                    WHEN refunded_count = quantity THEN 'REFUNDED'
                    WHEN refunded_count > 0 THEN 'PARTIALLY_REFUNDED'
                    ELSE 'PAID'
                END,
                used_count = used_count - 1,
                update_time = #{now}
            WHERE id = #{orderId}
              AND used_count > 0
            """)
    int decrementUsedAndRefreshStatus(@Param("orderId") Long orderId, @Param("now") java.time.LocalDateTime now);
}
