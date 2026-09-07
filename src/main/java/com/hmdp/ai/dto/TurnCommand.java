package com.hmdp.ai.dto;

import lombok.Data;

/** A small request-scoped semantic command. It is not persisted in Working Memory. */
@Data
public class TurnCommand {
    public enum Type {
        SET_CONSTRAINT,
        CLEAR_CONSTRAINT,
        EXCLUDE_CONSTRAINT,
        SET_LOCATION_INTENT,
        ASK_DECISION_CONTEXT,
        ASK_SHOP_FACT,
        BROADEN_FOOD_SCOPE,
        REFERENCE
    }

    private Type type;
    private String key;
    private String value;

    public TurnCommand() { }

    public TurnCommand(Type type, String key, String value) {
        this.type = type;
        this.key = key;
        this.value = value;
    }
}
