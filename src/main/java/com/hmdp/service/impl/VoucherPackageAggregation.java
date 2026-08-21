package com.hmdp.service.impl;

import com.hmdp.entity.VoucherCertificate;
import com.hmdp.entity.VoucherPackageOrder;

import java.util.List;

final class VoucherPackageAggregation {
    private VoucherPackageAggregation() {
    }

    static String resolve(VoucherPackageOrder order, List<VoucherCertificate> certificates) {
        if (certificates.size() != order.getQuantity()) {
            throw new IllegalArgumentException("券实例数量与套餐订单不一致");
        }
        long unused = certificates.stream().filter(item -> "UNUSED".equals(item.getStatus())).count();
        long used = certificates.stream().filter(item -> "USED".equals(item.getStatus())).count();
        return VoucherPackageStatus.resolve(order.getQuantity(), unused, used);
    }
}
