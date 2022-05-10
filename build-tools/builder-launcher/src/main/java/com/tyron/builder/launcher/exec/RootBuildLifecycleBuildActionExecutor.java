package com.tyron.builder.launcher.exec;

import com.tyron.builder.api.internal.BuildDefinition;
import com.tyron.builder.internal.invocation.BuildAction;
import com.tyron.builder.internal.build.BuildStateRegistry;
import com.tyron.builder.internal.build.RootBuildState;
import com.tyron.builder.internal.buildtree.BuildActionRunner;
import com.tyron.builder.internal.buildtree.BuildTreeActionExecutor;
import com.tyron.builder.internal.buildtree.BuildTreeContext;

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
