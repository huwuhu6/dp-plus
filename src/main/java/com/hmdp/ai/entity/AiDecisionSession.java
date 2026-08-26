package com.hmdp.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tbl_ai_decision_session")
public class AiDecisionSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String chatId;
    private String traceId;
    private Long startedEventId;
    private String queryText;
    private String status;
    private String constraintsJson;
    private String resultJson;
    private String requestContextJson;
    private String pendingType;
    private String pendingOptionsJson;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
