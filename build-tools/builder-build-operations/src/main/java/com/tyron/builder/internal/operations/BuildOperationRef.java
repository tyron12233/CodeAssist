package com.tyron.builder.internal.operations;

import org.jetbrains.annotations.Nullable;

import java.io.Serializable;

public interface BuildOperationRef extends Serializable {

    @Nullable
    OperationIdentifier getId();

    @Nullable
    OperationIdentifier getParentId();

}