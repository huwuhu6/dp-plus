package com.hmdp.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("tbl_ai_shop_profile")
public class AiShopProfile {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long shopId;
    private String cuisine;
    private String sceneTags;
    private String ambienceTags;
    private String queueLevel;
    private String summary;
    private Long inputRevision;
    private Long aggregatedRevision;
    private String profileStatus;
}
