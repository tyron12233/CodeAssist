package com.tyron.builder.launcher.exec;

import com.tyron.builder.internal.invocation.BuildAction;
import com.tyron.builder.internal.work.WorkerLeaseService;
import com.tyron.builder.internal.buildtree.BuildActionRunner;
import com.tyron.builder.internal.session.BuildSessionActionExecutor;
import com.tyron.builder.internal.session.BuildSessionContext;

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
