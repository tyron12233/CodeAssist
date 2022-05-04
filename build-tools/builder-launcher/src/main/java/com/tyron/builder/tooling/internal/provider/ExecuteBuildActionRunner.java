package com.tyron.builder.tooling.internal.provider;

import com.tyron.builder.internal.buildtree.BuildActionRunner;
import com.tyron.builder.internal.buildtree.BuildTreeLifecycleController;
import com.tyron.builder.internal.invocation.BuildAction;
import com.tyron.builder.tooling.internal.provider.action.ExecuteBuildAction;

public class ExecuteBuildActionRunner implements BuildActionRunner {
    @Override
    public Result run(BuildAction action, BuildTreeLifecycleController buildController) {
        if (!(action instanceof ExecuteBuildAction)) {
            return Result.nothing();
        }
        try {
            buildController.scheduleAndRunTasks();
            return Result.of(null);
        } catch (RuntimeException e) {
            return Result.failed(e);
        }
    }
}
