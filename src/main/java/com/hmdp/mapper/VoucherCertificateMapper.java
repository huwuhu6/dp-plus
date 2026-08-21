package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.VoucherCertificate;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface VoucherCertificateMapper extends BaseMapper<VoucherCertificate> {
    @Select("""
            SELECT id, package_order_id AS packageOrderId, certificate_no AS certificateNo, shop_id AS shopId, status,
                   used_time AS usedTime, refunded_time AS refundedTime, create_time AS createTime, update_time AS updateTime
            FROM tb_voucher_certificate
            WHERE package_order_id = #{packageOrderId}
            FOR UPDATE
            """)
    List<VoucherCertificate> selectByPackageOrderIdForUpdate(Long packageOrderId);
}
