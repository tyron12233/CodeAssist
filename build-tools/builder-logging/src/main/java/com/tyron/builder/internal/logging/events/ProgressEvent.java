package com.tyron.builder.internal.logging.events;

import com.tyron.builder.internal.logging.events.OutputEvent;
import com.tyron.builder.internal.operations.OperationIdentifier;
import com.tyron.builder.api.logging.LogLevel;

public class ProgressEvent extends OutputEvent {
    private final String status;
    private final boolean failing;
    private final OperationIdentifier progressOperationId;

    public ProgressEvent(OperationIdentifier progressOperationId, String status, boolean failing) {
        this.progressOperationId = progressOperationId;
        this.status = status;
        this.failing = failing;
    }

    public String getStatus() {
        return status;
    }

    public boolean isFailing() {
        return failing;
    }

    @Override
    public String toString() {
        return "Progress (" + progressOperationId + ") " + status;
    }

    public OperationIdentifier getProgressOperationId() {
        return progressOperationId;
    }

    @Override
    public LogLevel getLogLevel() {
        return LogLevel.LIFECYCLE;
    }
}
