package com.hmdp.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RestoreConversationStateResult {
    private Integer sourceVersion;
    private Integer expectedCurrentVersion;
    private Integer actualCurrentVersion;
    private Integer newVersion;
    private Long workingMemoryId;
    private List<String> warnings = new ArrayList<String>();
}
