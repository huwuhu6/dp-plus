package com.hmdp.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_decision_step")
public class AiDecisionStep {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private String state;
    private String summary;
    private Long durationMs;
    private LocalDateTime createTime;
}
