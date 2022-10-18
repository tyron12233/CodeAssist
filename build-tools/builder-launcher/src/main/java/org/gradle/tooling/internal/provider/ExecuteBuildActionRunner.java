package org.gradle.tooling.internal.provider;

import org.gradle.internal.buildtree.BuildActionRunner;
import org.gradle.internal.buildtree.BuildTreeLifecycleController;
import org.gradle.internal.invocation.BuildAction;
import org.gradle.tooling.internal.provider.action.ExecuteBuildAction;

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
