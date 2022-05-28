package com.tyron.builder.internal.logging.events;

import com.tyron.builder.internal.logging.events.CategorisedOutputEvent;
import com.tyron.builder.internal.logging.events.LogLevelConverter;
import com.tyron.builder.internal.operations.BuildOperationCategory;
import com.tyron.builder.internal.operations.OperationIdentifier;
import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.internal.logging.events.operations.ProgressStartBuildOperationProgressDetails;
import com.tyron.builder.internal.operations.logging.LogEventLevel;

import javax.annotation.Nullable;

@SuppressWarnings("deprecation")
public class ProgressStartEvent extends CategorisedOutputEvent implements ProgressStartBuildOperationProgressDetails {
    public static final String TASK_CATEGORY = "class com.tyron.builder.internal.buildevents.TaskExecutionLogger";
    public static final String BUILD_OP_CATEGORY = "com.tyron.builder.internal.logging.progress.ProgressLoggerFactory";

    private final OperationIdentifier progressOperationId;
    private final OperationIdentifier parentProgressOperationId;
    private final String description;
    private final @Nullable String loggingHeader;
    private final String status;
    private final int totalProgress;
    private final boolean buildOperationStart;
    private final @Nullable OperationIdentifier buildOperationId;
    private final BuildOperationCategory buildOperationCategory;

    public ProgressStartEvent(
            OperationIdentifier progressOperationId,
            @Nullable OperationIdentifier parentProgressOperationId,
            long timestamp,
            String category,
            String description,
            @Nullable String loggingHeader,
            String status,
            int totalProgress,
            boolean buildOperationStart,
            @Nullable OperationIdentifier buildOperationId,
            @Nullable BuildOperationCategory buildOperationCategory
    ) {
        super(timestamp, category, LogLevel.LIFECYCLE);
        this.progressOperationId = progressOperationId;
        this.parentProgressOperationId = parentProgressOperationId;
        this.description = description;
        this.loggingHeader = loggingHeader;
        this.status = status;
        this.totalProgress = totalProgress;
        this.buildOperationStart = buildOperationStart;
        this.buildOperationId = buildOperationId;
        this.buildOperationCategory = buildOperationCategory == null ? BuildOperationCategory.UNCATEGORIZED : buildOperationCategory;
    }

    @Nullable
    public OperationIdentifier getParentProgressOperationId() {
        return parentProgressOperationId;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    @Nullable
    public String getLoggingHeader() {
        return loggingHeader;
    }

    public String getStatus() {
        return status;
    }

    public int getTotalProgress() {
        return totalProgress;
    }

    @Override
    public String toString() {
        return "ProgressStart (p:" + progressOperationId + " parent p:" + parentProgressOperationId + " b:" + buildOperationId + ") " + description;
    }

    public OperationIdentifier getProgressOperationId() {
        return progressOperationId;
    }

    /**
     * Whether this progress start represent the start of a build operation,
     * as opposed to a progress operation within a build operation.
     */
    public boolean isBuildOperationStart() {
        return buildOperationStart;
    }

    /**
     * When this event is a build operation start event, this property will be non-null and will have the same value as {@link #getProgressOperationId()}.
     */
    @Nullable
    public OperationIdentifier getBuildOperationId() {
        return buildOperationId;
    }

    public BuildOperationCategory getBuildOperationCategory() {
        return buildOperationCategory;
    }

    public ProgressStartEvent withParentProgressOperation(OperationIdentifier parentProgressOperationId) {
        return new ProgressStartEvent(progressOperationId, parentProgressOperationId, getTimestamp(), getCategory(), description, loggingHeader, status, totalProgress, buildOperationStart, buildOperationId, buildOperationCategory);
    }

    @Override
    public LogEventLevel getLevel() {
        return LogLevelConverter.convert(getLogLevel());
    }
}