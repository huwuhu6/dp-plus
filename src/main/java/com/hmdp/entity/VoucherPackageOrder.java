package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("tb_voucher_package_order")
public class VoucherPackageOrder {
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;
    private Long userId;
    private Long voucherId;
    private Long shopId;
    private Integer quantity;
    private Long paidAmount;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
