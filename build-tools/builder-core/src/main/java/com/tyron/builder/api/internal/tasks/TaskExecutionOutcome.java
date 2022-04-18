package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.internal.execution.ExecutionOutcome;

import org.jetbrains.annotations.Nullable;

public enum TaskExecutionOutcome {
    FROM_CACHE(true, true, "FROM-CACHE"),
    UP_TO_DATE(true, true, "UP-TO-DATE"),
    SKIPPED(true, false, "SKIPPED"),
    NO_SOURCE(true, false, "NO-SOURCE"),
    EXECUTED(false, false, null);

    private final boolean skipped;
    private final boolean upToDate;
    private final String message;

    TaskExecutionOutcome(boolean skipped, boolean upToDate, @Nullable String message) {
        this.skipped = skipped;
        this.upToDate = upToDate;
        this.message = message;
    }

    public boolean isSkipped() {
        return skipped;
    }

    public boolean isUpToDate() {
        return upToDate;
    }

    @Nullable
    public String getMessage() {
        return message;
    }

    public static TaskExecutionOutcome valueOf(ExecutionOutcome outcome) {
        switch (outcome) {
            case FROM_CACHE:
                return FROM_CACHE;
            case UP_TO_DATE:
                return UP_TO_DATE;
            case SHORT_CIRCUITED:
                return NO_SOURCE;
            case EXECUTED_INCREMENTALLY:
            case EXECUTED_NON_INCREMENTALLY:
                return EXECUTED;
            default:
                throw new AssertionError();
        }
    }
}
