package com.tyron.builder.api.internal.changedetection;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Keeps information about the execution mode of a task.
 */
public enum TaskExecutionMode {
    INCREMENTAL(null, true, true),
    NO_OUTPUTS("Task has not declared any outputs despite executing actions.", false, false),
    RERUN_TASKS_ENABLED("Executed with '--rerun-tasks'.", true, false),
    UP_TO_DATE_WHEN_FALSE("Task.upToDateWhen is false.", true, false),
    UNTRACKED("Task state is not tracked.", false, false);

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private final Optional<String> rebuildReason;
    private final boolean taskHistoryMaintained;
    private final boolean allowedToUseCachedResults;

    TaskExecutionMode(@Nullable String rebuildReason, boolean taskHistoryMaintained, boolean allowedToUseCachedResults) {
        this.rebuildReason = Optional.ofNullable(rebuildReason);
        this.taskHistoryMaintained = taskHistoryMaintained;
        this.allowedToUseCachedResults = allowedToUseCachedResults;
    }

    /**
     * Return rebuild reason if any.
     */
    public Optional<String> getRebuildReason() {
        return rebuildReason;
    }

    /**
     * Returns whether the execution history should be stored.
     */
    public boolean isTaskHistoryMaintained() {
        return taskHistoryMaintained;
    }

    /**
     * Returns whether it is okay to use results loaded from cache instead of executing the task.
     */
    public boolean isAllowedToUseCachedResults() {
        return allowedToUseCachedResults;
    }
}