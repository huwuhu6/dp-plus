package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("tb_voucher_fulfillment_audit")
public class VoucherFulfillmentAudit {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long certificateId;
    private String requestId;
    private String action;
    private String result;
    private String operatorType;
    private Long operatorId;
    private String detail;
    private LocalDateTime createTime;
}
