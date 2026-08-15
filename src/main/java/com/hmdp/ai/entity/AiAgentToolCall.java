package com.hmdp.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_agent_tool_call")
public class AiAgentToolCall {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Integer turnNo;
    private String toolName;
    private String toolInputJson;
    private String toolOutputJson;
    private String status;
    private Long durationMs;
    private LocalDateTime createTime;
}
