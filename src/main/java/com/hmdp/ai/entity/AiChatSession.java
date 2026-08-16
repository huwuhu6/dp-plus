package com.hmdp.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_chat_session")
public class AiChatSession {
    @TableId(type = IdType.INPUT)
    private String chatId;
    private Long userId;
    private Long activeDecisionSessionId;
    private Long lastDecisionSessionId;
    private Integer version;
    private String slotsJson;
    private LocalDateTime updateTime;
}
