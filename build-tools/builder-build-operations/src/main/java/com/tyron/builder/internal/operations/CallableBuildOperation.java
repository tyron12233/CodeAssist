package com.tyron.builder.internal.operations;

public interface CallableBuildOperation<T> extends BuildOperation {

    T call(BuildOperationContext context) throws Exception;
}