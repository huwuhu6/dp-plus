package com.hmdp.ai.dto;

import lombok.Data;

/** A confirmed, structured request to restore a historical business-state snapshot. */
@Data
public class RestoreConversationStateCommand {
    public static final String BUSINESS_STATE = "BUSINESS_STATE";

    private String chatId;
    private Integer sourceVersion;
    private Integer expectedCurrentVersion;
    private String reason;
    private String scope = BUSINESS_STATE;
    private String commandId;
}
