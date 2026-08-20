package com.hmdp.service.impl;

import java.time.LocalDateTime;

final class VoucherVerificationCancellationPolicy {
    static final long WINDOW_MINUTES = 60;

    private VoucherVerificationCancellationPolicy() {
    }

    static boolean isEligible(LocalDateTime usedTime, LocalDateTime now) {
        return usedTime != null && !usedTime.isBefore(now.minusMinutes(WINDOW_MINUTES));
    }
}
