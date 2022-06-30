package com.tyron.builder.internal.operations;

import org.jetbrains.annotations.Nullable;

public class CurrentBuildOperationRef {

    private static final CurrentBuildOperationRef INSTANCE = new CurrentBuildOperationRef();

    private final ThreadLocal<BuildOperationRef> ref = new ThreadLocal<BuildOperationRef>();

    public static CurrentBuildOperationRef instance() {
        return INSTANCE;
    }

    @Nullable
    public BuildOperationRef get() {
        return ref.get();
    }

    @Nullable
    public OperationIdentifier getId() {
        BuildOperationRef operationState = get();
        return operationState == null ? null : operationState.getId();
    }

    @Nullable
    public OperationIdentifier getParentId() {
        BuildOperationRef operationState = get();
        return operationState == null ? null : operationState.getParentId();
    }

    public void set(@Nullable BuildOperationRef state) {
        if (state == null) {
            ref.remove();
        } else {
            ref.set(state);
        }
    }

    public void clear() {
        ref.remove();
    }

}