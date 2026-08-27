package com.hmdp.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("tbl_ai_review_document")
public class AiReviewDocument {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long shopId;
    private String sourceType;
    private String sourceKey;
    private String content;
    private String tags;
    private Integer sentiment;
}
