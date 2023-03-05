package org.gradle.internal.buildtree;

import org.gradle.internal.build.ExecutionResult;
import org.gradle.internal.enterprise.core.GradleEnterprisePluginManager;

public class ExecutionPhaseNotifyingBuildTreeWorkExecutor implements BuildTreeWorkExecutor {
    private final BuildTreeWorkExecutor delegate;
    private final GradleEnterprisePluginManager gradleEnterprisePluginManager;

    public ExecutionPhaseNotifyingBuildTreeWorkExecutor(BuildTreeWorkExecutor delegate, GradleEnterprisePluginManager gradleEnterprisePluginManager) {
        this.delegate = delegate;
        this.gradleEnterprisePluginManager = gradleEnterprisePluginManager;
    }

    @Override
    public ExecutionResult<Void> execute(BuildTreeWorkGraph graph) {
        gradleEnterprisePluginManager.executionPhaseStarted();
        return delegate.execute(graph);
    }
}