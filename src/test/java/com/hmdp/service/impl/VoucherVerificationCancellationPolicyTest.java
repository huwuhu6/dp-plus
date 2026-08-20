package com.hmdp.service.impl;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoucherVerificationCancellationPolicyTest {
    @Test
    void permitsOnlyUsedCertificatesInsideTheCancellationWindow() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 20, 10, 0);

        assertTrue(VoucherVerificationCancellationPolicy.isEligible(now.minusMinutes(60), now));
        assertTrue(VoucherVerificationCancellationPolicy.isEligible(now.minusMinutes(1), now));
        assertFalse(VoucherVerificationCancellationPolicy.isEligible(now.minusMinutes(61), now));
        assertFalse(VoucherVerificationCancellationPolicy.isEligible(null, now));
    }
}
