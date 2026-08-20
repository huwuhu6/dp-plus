package com.hmdp.service;

import com.hmdp.dto.VoucherCertificateActionRequest;
import com.hmdp.dto.VoucherPackageCreateRequest;
import com.hmdp.dto.Result;

public interface IVoucherFulfillmentService {
    Result createPaidPackage(VoucherPackageCreateRequest request);

    Result verify(String certificateNo, VoucherCertificateActionRequest request);

    Result cancelVerify(String certificateNo, VoucherCertificateActionRequest request);

    Result refund(String certificateNo, VoucherCertificateActionRequest request);
}
