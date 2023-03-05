package org.gradle.tooling.internal.provider.runner;

import org.gradle.api.tasks.testing.TestExecutionException;
import org.gradle.execution.BuildConfigurationActionExecuter;
import org.gradle.internal.buildtree.BuildActionRunner;
import org.gradle.internal.buildtree.BuildTreeLifecycleController;
import org.gradle.internal.invocation.BuildAction;
import org.gradle.internal.operations.BuildOperationAncestryTracker;
import org.gradle.internal.operations.BuildOperationListenerManager;
import org.gradle.tooling.internal.protocol.test.InternalTestExecutionException;
import org.gradle.tooling.internal.provider.action.TestExecutionRequestAction;

import java.util.Collections;

public class TestExecutionRequestActionRunner implements BuildActionRunner {
    private final BuildOperationAncestryTracker ancestryTracker;
    private final BuildOperationListenerManager buildOperationListenerManager;

    public TestExecutionRequestActionRunner(
        BuildOperationAncestryTracker ancestryTracker,
        BuildOperationListenerManager buildOperationListenerManager
    ) {
        this.ancestryTracker = ancestryTracker;
        this.buildOperationListenerManager = buildOperationListenerManager;
    }

    @Override
    public Result run(BuildAction action, BuildTreeLifecycleController buildController) {
        if (!(action instanceof TestExecutionRequestAction)) {
            return Result.nothing();
        }

        try {
            TestExecutionRequestAction testExecutionRequestAction = (TestExecutionRequestAction) action;
            TestExecutionResultEvaluator testExecutionResultEvaluator = new TestExecutionResultEvaluator(ancestryTracker, testExecutionRequestAction);
            buildOperationListenerManager.addListener(testExecutionResultEvaluator);
            try {
                doRun(testExecutionRequestAction, buildController);
            } finally {
                buildOperationListenerManager.removeListener(testExecutionResultEvaluator);
            }
            testExecutionResultEvaluator.evaluate();
        } catch (RuntimeException e) {
            Throwable throwable = findRootCause(e);
            if (throwable instanceof TestExecutionException) {
                return Result.failed(e, new InternalTestExecutionException("Error while running test(s)", throwable));
            } else {
                return Result.failed(e);
            }
        }

        return Result.of(null);
    }

    private void doRun(TestExecutionRequestAction action, BuildTreeLifecycleController buildController) {
        buildController.beforeBuild(gradle -> {
            TestExecutionBuildConfigurationAction testTasksConfigurationAction = new TestExecutionBuildConfigurationAction(action, gradle);
            gradle.getServices().get(BuildConfigurationActionExecuter.class).setTaskSelectors(Collections.singletonList(testTasksConfigurationAction));
        });
        buildController.scheduleAndRunTasks();
    }

    private Throwable findRootCause(Exception tex) {
        Throwable t = tex;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}