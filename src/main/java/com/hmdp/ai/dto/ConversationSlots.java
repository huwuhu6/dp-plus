package com.hmdp.ai.dto;

import lombok.Data;

@Data
public class ConversationSlots {
    private ConversationLocationSlot location = new ConversationLocationSlot();
}
