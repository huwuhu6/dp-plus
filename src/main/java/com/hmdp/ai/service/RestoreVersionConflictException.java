package com.hmdp.ai.service;

/** Exposes all versions involved in a rejected restore CAS check. */
public class RestoreVersionConflictException extends IllegalStateException {
    private final Integer sourceVersion;
    private final Integer expectedCurrentVersion;
    private final Integer actualCurrentVersion;

    public RestoreVersionConflictException(Integer sourceVersion, Integer expectedCurrentVersion, Integer actualCurrentVersion) {
        super("Working memory version conflict: expected " + expectedCurrentVersion + " but was " + actualCurrentVersion);
        this.sourceVersion = sourceVersion;
        this.expectedCurrentVersion = expectedCurrentVersion;
        this.actualCurrentVersion = actualCurrentVersion;
    }

    public Integer getSourceVersion() { return sourceVersion; }
    public Integer getExpectedCurrentVersion() { return expectedCurrentVersion; }
    public Integer getActualCurrentVersion() { return actualCurrentVersion; }
}
