package com.hmdp.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Canonical merchant-review fact. AI review documents are derived from this record. */
@Data
@TableName("tbl_shop_review")
public class ShopReview {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long shopId;
    private String sourceType;
    private String sourceKey;
    private Long userId;
    private Integer rating;
    private String content;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
