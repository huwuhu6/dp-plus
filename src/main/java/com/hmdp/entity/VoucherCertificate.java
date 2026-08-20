package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("tb_voucher_certificate")
public class VoucherCertificate {
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;
    private Long packageOrderId;
    private String certificateNo;
    private Long shopId;
    private String status;
    private LocalDateTime usedTime;
    private LocalDateTime refundedTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
