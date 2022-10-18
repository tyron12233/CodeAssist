package org.gradle.internal.operations;

public interface RunnableBuildOperation extends BuildOperation {

    void run(BuildOperationContext context) throws Exception;
}