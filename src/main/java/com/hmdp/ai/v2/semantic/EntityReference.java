package com.hmdp.ai.v2.semantic;

/** Only references with a current executable grounding contract are admitted to V2.0. */
public sealed interface EntityReference permits EntityReference.OrdinalRef, EntityReference.FocusedEntityRef,
        EntityReference.NamedEntityRef, EntityReference.NamedOrdinalRef, EntityReference.LastVisibleRef, EntityReference.TaskRef {
    record OrdinalRef(int ordinal) implements EntityReference { public OrdinalRef { if (ordinal < 1) throw new IllegalArgumentException("ordinal starts at one"); } }
    record FocusedEntityRef() implements EntityReference { }
    record NamedEntityRef(String name) implements EntityReference {
        public NamedEntityRef { if (name == null || name.isBlank()) throw new IllegalArgumentException("merchant name required"); }
    }
    /** 名称和序号是联合证据，不能在解析时丢掉名称只保留序号。 */
    record NamedOrdinalRef(String name, int ordinal) implements EntityReference {
        public NamedOrdinalRef { if (name == null || name.isBlank() || ordinal < 1) throw new IllegalArgumentException("name and positive ordinal required"); }
    }
    /** The final member of the projection currently visible to the user, never a model-guessed ordinal. */
    record LastVisibleRef() implements EntityReference { }
    record TaskRef(TaskSelector selector) implements EntityReference { }
}
