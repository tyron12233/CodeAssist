package com.tyron.builder.internal.operations;

import com.tyron.builder.internal.time.Clock;

import org.jetbrains.annotations.Nullable;

public class BuildOperationProgressEventEmitter {

    private final Clock clock;
    private final CurrentBuildOperationRef current;
    private final BuildOperationListener listener;

    public BuildOperationProgressEventEmitter(Clock clock, CurrentBuildOperationRef current, BuildOperationListener listener) {
        this.clock = clock;
        this.current = current;
        this.listener = listener;
    }

    public void emit(OperationIdentifier operationIdentifier, long timestamp, @Nullable Object details) {
        // Explicit check in case of unsafe CurrentBuildOperationRef usage
        if (operationIdentifier == null) {
            throw new IllegalArgumentException("operationIdentifier is null");
        }
        doEmit(operationIdentifier, timestamp, details);
    }

    public void emitNowIfCurrent(Object details) {
        emitIfCurrent(clock.getCurrentTime(), details);
    }

    public void emitIfCurrent(long time, Object details) {
        OperationIdentifier currentOperationIdentifier = current.getId();
        if (currentOperationIdentifier != null) {
            doEmit(currentOperationIdentifier, time, details);
        }
    }

    public void emitNowForCurrent(Object details) {
        emitForCurrent(clock.getCurrentTime(), details);
    }

    private void emitForCurrent(long time, Object details) {
        OperationIdentifier currentOperationIdentifier = current.getId();
        if (currentOperationIdentifier == null) {
            throw new IllegalStateException("No current build operation");
        } else {
            doEmit(currentOperationIdentifier, time, details);
        }
    }

    private void doEmit(OperationIdentifier operationIdentifier, long timestamp, @Nullable Object details) {
        listener.progress(operationIdentifier, new OperationProgressEvent(timestamp, details));
    }
}