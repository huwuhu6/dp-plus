package com.hmdp.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
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
    /** One-based prior turn whose displayed candidates must not reappear in later recommendations. */
    private Integer expectedUnseenFromTurn;
    /** One-based source/target turn pairs whose recommendation sets must be disjoint. */
    private String expectedUnseenPairsJson;
    /** JSONL-only fields. Marked non-persistent so legacy MySQL datasets remain readable. */
    @TableField(exist = false)
    private String expectedTurnStatesJson;
    @TableField(exist = false)
    private String expectedToolsByTurnJson;
    @TableField(exist = false)
    private String expectedRelationsJson;
    private Boolean active;
    private String notes;
}
