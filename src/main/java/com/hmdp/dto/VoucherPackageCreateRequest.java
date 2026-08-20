package com.hmdp.dto;

import lombok.Data;

@Data
public class VoucherPackageCreateRequest {
    private Long userId;
    private Long voucherId;
    private Long shopId;
    private Integer quantity;
    private Long paidAmount;
}
