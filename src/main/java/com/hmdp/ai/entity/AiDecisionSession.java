package com.hmdp.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_decision_session")
public class AiDecisionSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String queryText;
    private String status;
    private String constraintsJson;
    private String resultJson;
    private String requestContextJson;
    private String pendingType;
    private String pendingOptionsJson;
    private String agentContextJson;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
