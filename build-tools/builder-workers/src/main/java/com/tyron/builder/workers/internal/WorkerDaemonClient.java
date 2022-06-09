package com.tyron.builder.workers.internal;

import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.internal.concurrent.Stoppable;
import com.tyron.builder.process.internal.health.memory.JvmMemoryStatus;
import com.tyron.builder.process.internal.worker.MultiRequestClient;
import com.tyron.builder.process.internal.worker.WorkerProcess;

class WorkerDaemonClient implements Stoppable {
    public static final String DISABLE_EXPIRATION_PROPERTY_KEY = "com.tyron.builder.workers.internal.disable-daemons-expiration";
    private final DaemonForkOptions forkOptions;
    private final MultiRequestClient<TransportableActionExecutionSpec, DefaultWorkResult> workerClient;
    private final WorkerProcess workerProcess;
    private final LogLevel logLevel;
    private final ActionExecutionSpecFactory actionExecutionSpecFactory;
    private int uses;
    private boolean failed;
    private boolean cannotBeExpired = Boolean.getBoolean(DISABLE_EXPIRATION_PROPERTY_KEY);

    public WorkerDaemonClient(DaemonForkOptions forkOptions, MultiRequestClient<TransportableActionExecutionSpec, DefaultWorkResult> workerClient, WorkerProcess workerProcess, LogLevel logLevel, ActionExecutionSpecFactory actionExecutionSpecFactory) {
        this.forkOptions = forkOptions;
        this.workerClient = workerClient;
        this.workerProcess = workerProcess;
        this.logLevel = logLevel;
        this.actionExecutionSpecFactory = actionExecutionSpecFactory;
    }

    public DefaultWorkResult execute(IsolatedParametersActionExecutionSpec<?> spec) {
        uses++;
        return workerClient.run(actionExecutionSpecFactory.newTransportableSpec(spec));
    }

    public boolean isCompatibleWith(DaemonForkOptions required) {
        return forkOptions.isCompatibleWith(required);
    }

    JvmMemoryStatus getJvmMemoryStatus() {
        return workerProcess.getJvmMemoryStatus();
    }

    @Override
    public void stop() {
        workerClient.stop();
    }

    DaemonForkOptions getForkOptions() {
        return forkOptions;
    }

    public int getUses() {
        return uses;
    }

    public KeepAliveMode getKeepAliveMode() {
        return forkOptions.getKeepAliveMode();
    }

    public LogLevel getLogLevel() {
        return logLevel;
    }

    public boolean isProcess(WorkerProcess workerProcess) {
        return this.workerProcess.equals(workerProcess);
    }

    public boolean isFailed() {
        return failed;
    }

    public void setFailed(boolean failed) {
        this.failed = failed;
    }

    public boolean isNotExpirable() {
        return cannotBeExpired;
    }

    @Override
    public String toString() {
        return "WorkerDaemonClient{" +
            " log level=" + logLevel +
            ", use count=" + uses +
            ", has failed=" + failed +
            ", can be expired=" + !cannotBeExpired +
            ", workerProcess=" + workerProcess +
            ", forkOptions=" + forkOptions +
            '}';
    }
}
