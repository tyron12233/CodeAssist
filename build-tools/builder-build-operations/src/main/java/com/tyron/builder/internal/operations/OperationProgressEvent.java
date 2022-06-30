package com.tyron.builder.internal.operations;

import org.jetbrains.annotations.Nullable;

public final class OperationProgressEvent {

    private final long time;
    private final Object details;

    public OperationProgressEvent(long time, @Nullable Object details) {
        this.time = time;
        this.details = details;
    }

    public long getTime() {
        return time;
    }

    @Nullable
    public Object getDetails() {
        return details;
    }
}