package com.tyron.builder.launcher.exec;

import com.tyron.builder.api.internal.invocation.BuildAction;
import com.tyron.builder.internal.buildTree.BuildActionRunner;
import com.tyron.builder.internal.buildTree.BuildTreeLifecycleController;

import java.util.List;

import java.util.List;

public class ChainingBuildActionRunner implements BuildActionRunner {
    private final List<? extends BuildActionRunner> runners;

    public ChainingBuildActionRunner(List<? extends BuildActionRunner> runners) {
        this.runners = runners;
    }

    @Override
    public Result run(BuildAction action, BuildTreeLifecycleController buildController) {
        for (BuildActionRunner runner : runners) {
            Result result = runner.run(action, buildController);
            if (result.hasResult()) {
                return result;
            }
        }
        throw new UnsupportedOperationException(String.format("Don't know how to run a build action of type %s.", action.getClass().getSimpleName()));
    }
}
