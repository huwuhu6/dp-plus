package com.hmdp.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** The latest desired vector state for one stable Milvus document id. */
@Data
@TableName("tbl_ai_vector_sync_task")
public class AiVectorSyncTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String documentId;
    private String documentType;
    private Long entityId;
    private Long shopId;
    private String operation;
    private Long targetRevision;
    private String status;
    private Integer attemptCount;
    private LocalDateTime availableAt;
    private String leaseToken;
    private LocalDateTime leasedAt;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
