package com.tyron.builder.plugin.options;

public final class SyncOptions {

    public enum ErrorFormatMode {
        MACHINE_PARSABLE,
        HUMAN_READABLE
    }

    public enum EvaluationMode {
        /** Standard mode, errors should be breaking */
        STANDARD,
        /** IDE mode. Errors should not be breaking and should generate a SyncIssue instead. */
        IDE,
    }

    private SyncOptions() {}
}
