package com.tyron.builder.internal.operations;

public interface RunnableBuildOperation extends BuildOperation {

    void run(BuildOperationContext context) throws Exception;
}