package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherCertificateActionRequest;
import com.hmdp.dto.VoucherPackageCreateRequest;
import com.hmdp.service.IVoucherFulfillmentService;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/voucher-fulfillment")
public class VoucherFulfillmentController {
    private final IVoucherFulfillmentService voucherFulfillmentService;

    public VoucherFulfillmentController(IVoucherFulfillmentService voucherFulfillmentService) {
        this.voucherFulfillmentService = voucherFulfillmentService;
    }

    @PostMapping("/packages")
    public Result createPaidPackage(@RequestBody VoucherPackageCreateRequest request) {
        if (UserHolder.getUser() == null || !UserHolder.getUser().getId().equals(request.getUserId())) {
            return Result.fail("无权创建其他用户的套餐订单");
        }
        return voucherFulfillmentService.createPaidPackage(request);
    }

    @PostMapping("/certificates/{certificateNo}/verify")
    public Result verify(@PathVariable String certificateNo, @RequestBody VoucherCertificateActionRequest request) {
        if (!isCurrentUser(request)) return Result.fail("操作人身份不匹配");
        return voucherFulfillmentService.verify(certificateNo, request);
    }

    @PostMapping("/certificates/{certificateNo}/cancel-verify")
    public Result cancelVerify(@PathVariable String certificateNo, @RequestBody VoucherCertificateActionRequest request) {
        if (!isCurrentUser(request)) return Result.fail("操作人身份不匹配");
        return voucherFulfillmentService.cancelVerify(certificateNo, request);
    }

    @PostMapping("/certificates/{certificateNo}/refund")
    public Result refund(@PathVariable String certificateNo, @RequestBody VoucherCertificateActionRequest request) {
        if (!isCurrentUser(request)) return Result.fail("操作人身份不匹配");
        return voucherFulfillmentService.refund(certificateNo, request);
    }

    private boolean isCurrentUser(VoucherCertificateActionRequest request) {
        return request != null && request.getOperatorId() != null && UserHolder.getUser() != null
                && UserHolder.getUser().getId().equals(request.getOperatorId());
    }
}
