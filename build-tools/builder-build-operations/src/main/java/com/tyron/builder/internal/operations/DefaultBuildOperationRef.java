package com.tyron.builder.internal.operations;

import org.jetbrains.annotations.Nullable;

public class DefaultBuildOperationRef implements BuildOperationRef {

    private final OperationIdentifier id;
    private final OperationIdentifier parentId;

    public DefaultBuildOperationRef(@Nullable OperationIdentifier id, @Nullable OperationIdentifier parentId) {
        this.id = id;
        this.parentId = parentId;
    }

    @Override
    public OperationIdentifier getId() {
        return id;
    }

    @Override
    public OperationIdentifier getParentId() {
        return parentId;
    }

}