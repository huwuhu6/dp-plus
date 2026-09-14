package com.hmdp.ai.v2.semantic;

public sealed interface EntityReference permits EntityReference.OrdinalRef, EntityReference.FocusedEntityRef,
        EntityReference.NamedEntityRef, EntityReference.TaskRef, EntityReference.HistoricalEntityRef,
        EntityReference.PoiMention, EntityReference.CuisineMention {
    record OrdinalRef(int ordinal) implements EntityReference { public OrdinalRef { if (ordinal < 1) throw new IllegalArgumentException("ordinal starts at one"); } }
    record FocusedEntityRef() implements EntityReference { }
    record NamedEntityRef(String name) implements EntityReference { }
    record TaskRef(String description) implements EntityReference { }
    record HistoricalEntityRef(String description) implements EntityReference { }
    record PoiMention(String text) implements EntityReference { }
    record CuisineMention(String text) implements EntityReference { }
}
