package com.tyron.builder.internal.operations;

public final class OperationStartEvent {
    private final long startTime;

    public OperationStartEvent(long startTime) {
        this.startTime = startTime;
    }

    public long getStartTime() {
        return startTime;
    }
}