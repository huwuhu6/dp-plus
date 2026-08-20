package com.hmdp.dto;

import lombok.Data;

@Data
public class VoucherCertificateActionRequest {
    private String requestId;
    private Long operatorId;
    private Long shopId;
}
