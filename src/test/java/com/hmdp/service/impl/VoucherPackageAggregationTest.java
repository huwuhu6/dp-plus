package com.hmdp.service.impl;

import com.hmdp.entity.VoucherCertificate;
import com.hmdp.entity.VoucherPackageOrder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VoucherPackageAggregationTest {
    @Test
    void resolvesFinalOrderStatusFromAllLockedCertificateStates() {
        VoucherPackageOrder order = new VoucherPackageOrder().setQuantity(2);

        assertEquals("USED", VoucherPackageAggregation.resolve(order, List.of(
                certificate("USED"), certificate("USED"))));
        assertEquals("PARTIALLY_USED", VoucherPackageAggregation.resolve(order, List.of(
                certificate("USED"), certificate("REFUNDED"))));
    }

    private VoucherCertificate certificate(String status) {
        return new VoucherCertificate().setStatus(status);
    }
}
