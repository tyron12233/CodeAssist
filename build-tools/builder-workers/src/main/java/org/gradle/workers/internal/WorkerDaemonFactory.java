package org.gradle.workers.internal;

import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.BuildOperationRef;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Controls the lifecycle of the worker daemon and provides access to it.
 */
@ThreadSafe
public class WorkerDaemonFactory implements WorkerFactory {
    private final WorkerDaemonClientsManager clientsManager;
    private final BuildOperationExecutor buildOperationExecutor;

    public WorkerDaemonFactory(WorkerDaemonClientsManager clientsManager, BuildOperationExecutor buildOperationExecutor) {
        this.clientsManager = clientsManager;
        this.buildOperationExecutor = buildOperationExecutor;
    }

    @Override
    public BuildOperationAwareWorker getWorker(WorkerRequirement workerRequirement) {
        return new AbstractWorker(buildOperationExecutor) {
            @Override
            public DefaultWorkResult execute(IsolatedParametersActionExecutionSpec<?> spec, BuildOperationRef parentBuildOperation) {
                final WorkerDaemonClient client = reserveClient();
                try {
                    return executeWrappedInBuildOperation(spec, parentBuildOperation, client::execute);
                } finally {
                    clientsManager.release(client);
                }
            }

            private WorkerDaemonClient reserveClient() {
                DaemonForkOptions forkOptions = ((ForkedWorkerRequirement) workerRequirement).getForkOptions();
                WorkerDaemonClient client = clientsManager.reserveIdleClient(forkOptions);
                if (client == null) {
                    client = clientsManager.reserveNewClient(forkOptions);
                }
                return client;
            }
        };
    }

    @Override
    public IsolationMode getIsolationMode() {
        return IsolationMode.PROCESS;
    }
}
