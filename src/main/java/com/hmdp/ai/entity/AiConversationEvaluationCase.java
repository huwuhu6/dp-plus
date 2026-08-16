package com.hmdp.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("ai_conversation_evaluation_case")
public class AiConversationEvaluationCase {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String caseCode;
    private String datasetVersion;
    private String turnsJson;
    private String expectedRoutesJson;
    private String expectedToolNamesJson;
    private String expectedFinalStatus;
    private String expectedShopIds;
    private String expectedCity;
    private Boolean active;
    private String notes;
}
