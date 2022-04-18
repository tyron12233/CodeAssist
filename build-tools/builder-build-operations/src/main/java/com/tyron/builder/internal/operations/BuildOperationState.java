package com.tyron.builder.internal.operations;

import java.io.ObjectStreamException;
import java.util.concurrent.atomic.AtomicBoolean;

public class BuildOperationState implements BuildOperationRef {
    private final BuildOperationDescriptor description;
    private final AtomicBoolean running = new AtomicBoolean();
    private final long startTime;

    public BuildOperationState(BuildOperationDescriptor descriptor, long startTime) {
        this.startTime = startTime;
        this.description = descriptor;
    }

    public BuildOperationDescriptor getDescription() {
        return description;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void setRunning(boolean running) {
        this.running.set(running);
    }

    public long getStartTime() {
        return startTime;
    }

    @Override
    public OperationIdentifier getId() {
        return description.getId();
    }

    @Override
    public OperationIdentifier getParentId() {
        return description.getParentId();
    }

    @Override
    public String toString() {
        return getDescription().getDisplayName();
    }

    private Object writeReplace() throws ObjectStreamException {
        return new DefaultBuildOperationRef(description.getId(), description.getParentId());
    }
}