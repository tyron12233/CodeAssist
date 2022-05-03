package com.tyron.builder.launcher.exec;

import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.buildtree.BuildActionRunner;
import com.tyron.builder.internal.buildtree.BuildTreeLifecycleController;
import com.tyron.builder.internal.enterprise.core.GradleEnterprisePluginManager;
import com.tyron.builder.internal.invocation.BuildAction;

/**
 * An {@link BuildActionRunner} that notifies the GE plugin manager that the build has completed.
 */
public class BuildCompletionNotifyingBuildActionRunner implements BuildActionRunner {
    private final BuildActionRunner delegate;
    private final GradleEnterprisePluginManager gradleEnterprisePluginManager;

    public BuildCompletionNotifyingBuildActionRunner(BuildActionRunner delegate, GradleEnterprisePluginManager gradleEnterprisePluginManager) {
        this.delegate = delegate;
        this.gradleEnterprisePluginManager = gradleEnterprisePluginManager;
    }

    @Override
    public Result run(final BuildAction action, BuildTreeLifecycleController buildController) {
        Result result;
        try {
            result = delegate.run(action, buildController);
        } catch (Throwable t) {
            // Note: throw the failure rather than returning a result object containing the failure, as console failure logging based on the _result_ happens down in the root build scope
            // whereas console failure logging based on the _thrown exception_ happens up outside session scope. It would be better to refactor so that a result can be returned from here
            notifyEnterprisePluginManager(Result.failed(t));
            throw UncheckedException.throwAsUncheckedException(t);
        }
        notifyEnterprisePluginManager(result);
        return result;
    }

    private void notifyEnterprisePluginManager(Result result) {
        gradleEnterprisePluginManager.buildFinished(result.getBuildFailure());
    }
}
