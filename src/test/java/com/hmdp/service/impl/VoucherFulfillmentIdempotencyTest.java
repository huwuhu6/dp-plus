package com.hmdp.service.impl;

import com.hmdp.entity.VoucherFulfillmentAudit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoucherFulfillmentIdempotencyTest {
    private final VoucherFulfillmentAudit audit = new VoucherFulfillmentAudit()
            .setAction("VERIFY").setCertificateId(101L).setOperatorId(201L);

    @Test
    void replaysOnlyTheSameBusinessOperation() {
        assertTrue(VoucherFulfillmentIdempotency.matches(audit, "VERIFY", 101L, 201L));
        assertFalse(VoucherFulfillmentIdempotency.matches(audit, "REFUND", 101L, 201L));
        assertFalse(VoucherFulfillmentIdempotency.matches(audit, "VERIFY", 102L, 201L));
        assertFalse(VoucherFulfillmentIdempotency.matches(audit, "VERIFY", 101L, 202L));
    }
}
