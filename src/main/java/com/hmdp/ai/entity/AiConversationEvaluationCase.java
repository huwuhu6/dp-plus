package com.hmdp.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("tbl_ai_conversation_evaluation_case")
public class AiConversationEvaluationCase {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String caseCode;
    private String datasetVersion;
    private String turnsJson;
    private String expectedRoutesJson;
    private String expectedContextRewritesJson;
    private String expectedToolNamesJson;
    private String expectedToolArgumentsJson;
    private String expectedFinalStatus;
    private String expectedShopIds;
    private String expectedCity;
    /** Expected recoverable errors across the scripted turns. */
    private Integer expectedErrorCount;
    /** Routes observed after the first expected error; optional for non-recovery cases. */
    private String expectedRecoveryRoutesJson;
    /** Minimal final Working Memory assertions, never a serialized memory snapshot. */
    private String expectedMemoryJson;
    private Boolean active;
    private String notes;
}
