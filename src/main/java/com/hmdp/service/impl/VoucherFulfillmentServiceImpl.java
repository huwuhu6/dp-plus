package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherCertificateActionRequest;
import com.hmdp.dto.VoucherPackageCreateRequest;
import com.hmdp.entity.VoucherCertificate;
import com.hmdp.entity.VoucherFulfillmentAudit;
import com.hmdp.entity.VoucherPackageOrder;
import com.hmdp.mapper.VoucherCertificateMapper;
import com.hmdp.mapper.VoucherFulfillmentAuditMapper;
import com.hmdp.mapper.VoucherPackageOrderMapper;
import com.hmdp.service.IVoucherFulfillmentService;
import com.hmdp.utils.RedisIdWorker;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class VoucherFulfillmentServiceImpl implements IVoucherFulfillmentService {
    private static final String UNUSED = "UNUSED";
    private static final String USED = "USED";
    private static final String REFUNDED = "REFUNDED";

    private final VoucherPackageOrderMapper packageOrderMapper;
    private final VoucherCertificateMapper certificateMapper;
    private final VoucherFulfillmentAuditMapper auditMapper;
    private final RedisIdWorker redisIdWorker;

    public VoucherFulfillmentServiceImpl(VoucherPackageOrderMapper packageOrderMapper,
                                         VoucherCertificateMapper certificateMapper,
                                         VoucherFulfillmentAuditMapper auditMapper,
                                         RedisIdWorker redisIdWorker) {
        this.packageOrderMapper = packageOrderMapper;
        this.certificateMapper = certificateMapper;
        this.auditMapper = auditMapper;
        this.redisIdWorker = redisIdWorker;
    }

    @Override
    @Transactional
    public Result createPaidPackage(VoucherPackageCreateRequest request) {
        if (request == null || request.getUserId() == null || request.getVoucherId() == null || request.getShopId() == null
                || request.getQuantity() == null || request.getQuantity() <= 0 || request.getPaidAmount() == null
                || request.getPaidAmount() < 0) {
            return Result.fail("套餐订单参数不完整");
        }
        LocalDateTime now = LocalDateTime.now();
        long orderId = redisIdWorker.nextId("voucher-package-order");
        VoucherPackageOrder order = new VoucherPackageOrder()
                .setId(orderId).setUserId(request.getUserId()).setVoucherId(request.getVoucherId())
                .setShopId(request.getShopId()).setQuantity(request.getQuantity()).setPaidAmount(request.getPaidAmount())
                .setStatus("PAID").setCreateTime(now).setUpdateTime(now);
        packageOrderMapper.insert(order);

        List<VoucherCertificate> certificates = new ArrayList<>();
        for (int index = 1; index <= request.getQuantity(); index++) {
            long certificateId = redisIdWorker.nextId("voucher-certificate");
            certificates.add(new VoucherCertificate().setId(certificateId).setPackageOrderId(orderId)
                    .setCertificateNo("VC" + certificateId).setShopId(request.getShopId()).setStatus(UNUSED)
                    .setCreateTime(now).setUpdateTime(now));
        }
        for (VoucherCertificate certificate : certificates) certificateMapper.insert(certificate);
        return Result.ok(certificates.stream().map(VoucherCertificate::getCertificateNo).toList());
    }

    @Override
    @Transactional
    public Result verify(String certificateNo, VoucherCertificateActionRequest request) {
        return changeCertificate(certificateNo, request, "VERIFY", USED, "MERCHANT", true);
    }

    @Override
    @Transactional
    public Result cancelVerify(String certificateNo, VoucherCertificateActionRequest request) {
        if (certificateNo == null || certificateNo.isBlank() || request == null || request.getRequestId() == null
                || request.getRequestId().isBlank() || request.getOperatorId() == null || request.getShopId() == null) {
            return Result.fail("履约请求参数不完整");
        }
        VoucherCertificate certificate = certificateMapper.selectOne(new LambdaUpdateWrapper<VoucherCertificate>()
                .eq(VoucherCertificate::getCertificateNo, certificateNo));
        if (certificate == null) return Result.fail("券码不存在");
        VoucherFulfillmentAudit duplicate = findAudit(request.getRequestId());
        if (duplicate != null) return replay(duplicate, "CANCEL_VERIFY", certificate.getId(), request.getOperatorId());
        VoucherFulfillmentAudit audit = audit(certificate.getId(), request, "CANCEL_VERIFY", "PROCESSING", "MERCHANT", null);
        try {
            auditMapper.insert(audit);
        } catch (DuplicateKeyException e) {
            return replay(findAudit(request.getRequestId()), "CANCEL_VERIFY", certificate.getId(), request.getOperatorId());
        }
        if (!certificate.getShopId().equals(request.getShopId())) {
            return reject(audit, "门店无权撤销该券核销");
        }

        LocalDateTime now = LocalDateTime.now();
        if (!VoucherVerificationCancellationPolicy.isEligible(certificate.getUsedTime(), now)) {
            return reject(audit, "仅支持撤销 60 分钟内的已核销券");
        }
        int changed = certificateMapper.update(null, new LambdaUpdateWrapper<VoucherCertificate>()
                .eq(VoucherCertificate::getId, certificate.getId()).eq(VoucherCertificate::getStatus, USED)
                .ge(VoucherCertificate::getUsedTime, now.minusMinutes(VoucherVerificationCancellationPolicy.WINDOW_MINUTES))
                .set(VoucherCertificate::getStatus, UNUSED).set(VoucherCertificate::getUsedTime, null)
                .set(VoucherCertificate::getUpdateTime, now));
        if (changed != 1) return reject(audit, "券状态已被其他履约操作改变或已超过撤销时限");

        audit.setResult("SUCCESS").setDetail("shopId=" + request.getShopId() + ", cancelWindowMinutes="
                + VoucherVerificationCancellationPolicy.WINDOW_MINUTES);
        auditMapper.updateById(audit);
        refreshOrderStatus(certificate.getPackageOrderId());
        return Result.ok(audit);
    }

    @Override
    @Transactional
    public Result refund(String certificateNo, VoucherCertificateActionRequest request) {
        return changeCertificate(certificateNo, request, "REFUND", REFUNDED, "USER", false);
    }

    private Result changeCertificate(String certificateNo, VoucherCertificateActionRequest request, String action,
                                     String targetStatus, String operatorType, boolean requireShopMatch) {
        if (certificateNo == null || certificateNo.isBlank() || request == null || request.getRequestId() == null
                || request.getRequestId().isBlank() || request.getOperatorId() == null) {
            return Result.fail("履约请求参数不完整");
        }
        VoucherCertificate certificate = certificateMapper.selectOne(new LambdaUpdateWrapper<VoucherCertificate>()
                .eq(VoucherCertificate::getCertificateNo, certificateNo));
        if (certificate == null) return Result.fail("券码不存在");
        VoucherFulfillmentAudit duplicate = findAudit(request.getRequestId());
        if (duplicate != null) return replay(duplicate, action, certificate.getId(), request.getOperatorId());

        VoucherFulfillmentAudit audit = audit(certificate.getId(), request, action, "PROCESSING", operatorType, null);
        try {
            auditMapper.insert(audit);
        } catch (DuplicateKeyException e) {
            return replay(findAudit(request.getRequestId()), action, certificate.getId(), request.getOperatorId());
        }

        if (requireShopMatch && !certificate.getShopId().equals(request.getShopId())) {
            return reject(audit, "门店无权核销该券");
        }
        if (!requireShopMatch) {
            VoucherPackageOrder order = packageOrderMapper.selectById(certificate.getPackageOrderId());
            if (order == null || !order.getUserId().equals(request.getOperatorId())) {
                return reject(audit, "无权退款该券");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<VoucherCertificate> cas = new LambdaUpdateWrapper<VoucherCertificate>()
                .eq(VoucherCertificate::getId, certificate.getId()).eq(VoucherCertificate::getStatus, UNUSED)
                .set(VoucherCertificate::getStatus, targetStatus).set(VoucherCertificate::getUpdateTime, now);
        if (USED.equals(targetStatus)) cas.set(VoucherCertificate::getUsedTime, now);
        else cas.set(VoucherCertificate::getRefundedTime, now);

        if (certificateMapper.update(null, cas) != 1) {
            return reject(audit, "券状态已被其他履约操作改变");
        }

        audit.setCertificateId(certificate.getId()).setResult("SUCCESS")
                .setDetail(requireShopMatch ? "shopId=" + request.getShopId() : "未使用券退款");
        auditMapper.updateById(audit);
        refreshOrderStatus(certificate.getPackageOrderId());
        return Result.ok(audit);
    }

    private Result reject(VoucherFulfillmentAudit audit, String detail) {
        audit.setResult("REJECTED").setDetail(detail);
        auditMapper.updateById(audit);
        return Result.fail(detail);
    }

    private Result replay(VoucherFulfillmentAudit audit, String action, Long certificateId, Long operatorId) {
        if (audit == null) return Result.fail("请求状态未知，请使用新的请求号重试");
        if (!VoucherFulfillmentIdempotency.matches(audit, action, certificateId, operatorId)) {
            return Result.fail("请求号已用于其他履约操作，请使用新的请求号");
        }
        return "SUCCESS".equals(audit.getResult()) ? Result.ok(audit) : Result.fail(audit.getDetail());
    }

    private VoucherFulfillmentAudit audit(Long certificateId, VoucherCertificateActionRequest request, String action,
                                          String result, String operatorType, String detail) {
        return new VoucherFulfillmentAudit().setCertificateId(certificateId).setRequestId(request.getRequestId())
                .setAction(action).setResult(result).setOperatorType(operatorType).setOperatorId(request.getOperatorId())
                .setDetail(detail).setCreateTime(LocalDateTime.now());
    }

    private VoucherFulfillmentAudit findAudit(String requestId) {
        return auditMapper.selectOne(new LambdaUpdateWrapper<VoucherFulfillmentAudit>()
                .eq(VoucherFulfillmentAudit::getRequestId, requestId));
    }

    private void refreshOrderStatus(Long orderId) {
        VoucherPackageOrder order = packageOrderMapper.selectById(orderId);
        if (order == null) return;
        long unused = certificateMapper.selectCount(new LambdaUpdateWrapper<VoucherCertificate>()
                .eq(VoucherCertificate::getPackageOrderId, orderId).eq(VoucherCertificate::getStatus, UNUSED));
        long used = certificateMapper.selectCount(new LambdaUpdateWrapper<VoucherCertificate>()
                .eq(VoucherCertificate::getPackageOrderId, orderId).eq(VoucherCertificate::getStatus, USED));
        String status = VoucherPackageStatus.resolve(order.getQuantity(), unused, used);
        packageOrderMapper.update(null, new LambdaUpdateWrapper<VoucherPackageOrder>()
                .eq(VoucherPackageOrder::getId, orderId).set(VoucherPackageOrder::getStatus, status)
                .set(VoucherPackageOrder::getUpdateTime, LocalDateTime.now()));
    }
}
