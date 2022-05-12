package com.tyron.builder.internal.buildtree;

import com.tyron.builder.internal.build.ExecutionResult;

public class DefaultBuildTreeWorkExecutor implements BuildTreeWorkExecutor {
    @Override
    public ExecutionResult<Void> execute(BuildTreeWorkGraph graph) {
        return graph.runWork();
    }
}