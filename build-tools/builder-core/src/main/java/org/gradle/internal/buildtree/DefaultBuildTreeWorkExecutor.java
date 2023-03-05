package org.gradle.internal.buildtree;

import org.gradle.internal.build.ExecutionResult;

public class DefaultBuildTreeWorkExecutor implements BuildTreeWorkExecutor {
    @Override
    public ExecutionResult<Void> execute(BuildTreeWorkGraph graph) {
        return graph.runWork();
    }
}