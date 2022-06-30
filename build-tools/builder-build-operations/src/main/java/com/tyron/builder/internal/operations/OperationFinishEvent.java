package com.tyron.builder.internal.operations;

import org.jetbrains.annotations.Nullable;

public final class OperationFinishEvent {
    private final long startTime;
    private final long endTime;
    private final Throwable failure;
    private final Object result;

    public OperationFinishEvent(long startTime, long endTime, @Nullable Throwable failure, @Nullable Object result) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.failure = failure;
        this.result = result;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    @Nullable
    public Throwable getFailure() {
        return failure;
    }

    @Nullable
    public Object getResult() {
        return result;
    }
}