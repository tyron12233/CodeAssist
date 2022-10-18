package org.gradle.launcher.exec;

import org.gradle.api.internal.BuildDefinition;
import org.gradle.internal.invocation.BuildAction;
import org.gradle.internal.build.BuildStateRegistry;
import org.gradle.internal.build.RootBuildState;
import org.gradle.internal.buildtree.BuildActionRunner;
import org.gradle.internal.buildtree.BuildTreeActionExecutor;
import org.gradle.internal.buildtree.BuildTreeContext;

public class RootBuildLifecycleBuildActionExecutor implements BuildTreeActionExecutor {
    private final BuildActionRunner buildActionRunner;
    private final BuildStateRegistry buildStateRegistry;

    public RootBuildLifecycleBuildActionExecutor(BuildStateRegistry buildStateRegistry,
                                                 BuildActionRunner buildActionRunner) {
        this.buildActionRunner = buildActionRunner;
        this.buildStateRegistry = buildStateRegistry;
    }

    @Override
    public BuildActionRunner.Result execute(BuildAction action, BuildTreeContext buildTreeContext) {
        RootBuildState rootBuild = buildStateRegistry.createRootBuild(BuildDefinition.fromStartParameter(action.getStartParameter(), null));
        return rootBuild.run(buildController -> buildActionRunner.run(action, buildController));
    }
}
