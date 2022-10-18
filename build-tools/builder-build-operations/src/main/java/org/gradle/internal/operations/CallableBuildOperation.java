package org.gradle.internal.operations;

public interface CallableBuildOperation<T> extends BuildOperation {

    T call(BuildOperationContext context) throws Exception;
}