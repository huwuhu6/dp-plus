package com.hmdp.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VoucherPackageStatusTest {
    @Test
    void derivesOrderStatusFromIndependentCertificateStates() {
        assertEquals("PAID", VoucherPackageStatus.resolve(3, 3, 0));
        assertEquals("PARTIALLY_USED", VoucherPackageStatus.resolve(3, 1, 1));
        assertEquals("PARTIALLY_REFUNDED", VoucherPackageStatus.resolve(3, 2, 0));
        assertEquals("USED", VoucherPackageStatus.resolve(3, 0, 3));
        assertEquals("REFUNDED", VoucherPackageStatus.resolve(3, 0, 0));
    }

    @Test
    void rejectsImpossibleCertificateCounts() {
        assertThrows(IllegalArgumentException.class, () -> VoucherPackageStatus.resolve(3, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> VoucherPackageStatus.resolve(0, 0, 0));
    }

    @Test
    void derivesTheSameStatusFromPersistedCounters() {
        assertEquals("PAID", VoucherPackageStatus.resolveFromCounters(3, 0, 0));
        assertEquals("PARTIALLY_USED", VoucherPackageStatus.resolveFromCounters(3, 1, 1));
        assertEquals("PARTIALLY_REFUNDED", VoucherPackageStatus.resolveFromCounters(3, 0, 1));
        assertEquals("USED", VoucherPackageStatus.resolveFromCounters(3, 3, 0));
        assertEquals("REFUNDED", VoucherPackageStatus.resolveFromCounters(3, 0, 3));
        assertThrows(IllegalArgumentException.class, () -> VoucherPackageStatus.resolveFromCounters(3, 2, 2));
    }
}
