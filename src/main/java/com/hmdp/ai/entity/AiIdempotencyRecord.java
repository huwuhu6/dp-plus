package com.hmdp.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable deduplication record. Redis is deliberately not an idempotency authority. */
@Data
@TableName("tbl_ai_idempotency_record")
public class AiIdempotencyRecord {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private String chatId;
    private String scope;
    private String idempotencyKey;
    private String requestHash;
    private String status;
    private String resultReference;
    private String resultJson;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
