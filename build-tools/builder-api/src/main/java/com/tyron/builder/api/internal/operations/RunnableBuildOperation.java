package com.tyron.builder.api.internal.operations;

public interface RunnableBuildOperation extends BuildOperation {

    void run(BuildOperationContext context) throws Exception;
}