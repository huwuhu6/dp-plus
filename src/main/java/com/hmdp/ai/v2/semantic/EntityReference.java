package com.hmdp.ai.v2.semantic;

/** Only references with a current executable grounding contract are admitted to V2.0. */
public sealed interface EntityReference permits EntityReference.OrdinalRef, EntityReference.FocusedEntityRef,
        EntityReference.NamedEntityRef, EntityReference.TaskRef {
    record OrdinalRef(int ordinal) implements EntityReference { public OrdinalRef { if (ordinal < 1) throw new IllegalArgumentException("ordinal starts at one"); } }
    record FocusedEntityRef() implements EntityReference { }
    record NamedEntityRef(String name) implements EntityReference { }
    record TaskRef(TaskSelector selector) implements EntityReference { }
}
