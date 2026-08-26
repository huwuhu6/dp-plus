package com.hmdp.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("tbl_ai_evaluation_case")
public class AiEvaluationCase {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String caseCode;
    private String queryText;
    private Double latitude;
    private Double longitude;
    private Integer maxCandidates;
    private String expectedStatus;
    private String expectedFinalStatus;
    private String expectedShopIds;
    private String expectedConstraintsJson;
    private String followUpOptionId;
    private Double followUpLatitude;
    private Double followUpLongitude;
    private Boolean active;
    private String notes;
}
