package com.hmdp.service.impl;

import com.hmdp.entity.VoucherFulfillmentAudit;

final class VoucherFulfillmentIdempotency {
    private VoucherFulfillmentIdempotency() {
    }

    static boolean matches(VoucherFulfillmentAudit audit, String action, Long certificateId, Long operatorId) {
        return audit != null && action.equals(audit.getAction()) && certificateId.equals(audit.getCertificateId())
                && operatorId.equals(audit.getOperatorId());
    }
}
