package com.hmdp.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tbl_ai_conversation_event")
public class AiConversationEvent {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String chatId;
    private String traceId;
    private Integer turnNo;
    private Integer sequenceNo;
    private String eventType;
    private String status;
    private Long workingMemoryId;
    private Long parentEventId;
    private String eventResult;
    private String metadata;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private LocalDateTime createdAt;
}
