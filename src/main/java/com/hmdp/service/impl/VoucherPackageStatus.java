package com.hmdp.service.impl;

final class VoucherPackageStatus {
    private VoucherPackageStatus() {
    }

    static String resolve(int quantity, long unused, long used) {
        if (quantity <= 0 || unused < 0 || used < 0 || unused + used > quantity) {
            throw new IllegalArgumentException("套餐券实例计数不合法");
        }
        if (unused == quantity) return "PAID";
        if (unused == 0 && used == 0) return "REFUNDED";
        if (used == quantity) return "USED";
        return used == 0 ? "PARTIALLY_REFUNDED" : "PARTIALLY_USED";
    }
}
