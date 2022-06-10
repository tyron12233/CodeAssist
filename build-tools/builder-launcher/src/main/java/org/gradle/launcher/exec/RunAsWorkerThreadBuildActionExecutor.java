package org.gradle.launcher.exec;

import org.gradle.internal.invocation.BuildAction;
import org.gradle.internal.work.WorkerLeaseService;
import org.gradle.internal.buildtree.BuildActionRunner;
import org.gradle.internal.session.BuildSessionActionExecutor;
import org.gradle.internal.session.BuildSessionContext;

public class RunAsWorkerThreadBuildActionExecutor implements BuildSessionActionExecutor {
    private final BuildSessionActionExecutor delegate;
    private final WorkerLeaseService workerLeaseService;

    public RunAsWorkerThreadBuildActionExecutor(WorkerLeaseService workerLeaseService, BuildSessionActionExecutor delegate) {
        this.delegate = delegate;
        this.workerLeaseService = workerLeaseService;
    }

    @Override
    public BuildActionRunner.Result execute(BuildAction action, BuildSessionContext context) {
        return workerLeaseService.runAsWorkerThread(() -> delegate.execute(action, context));
    }
}
