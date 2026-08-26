package com.hmdp.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tbl_ai_working_memory")
public class AiWorkingMemory {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String chatId;
    private Long userId;
    private Integer version;
    private String memoryJson;
    private LocalDateTime createdAt;
}
