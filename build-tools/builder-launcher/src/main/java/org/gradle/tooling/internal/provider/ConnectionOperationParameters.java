package org.gradle.tooling.internal.provider;

import org.gradle.launcher.daemon.configuration.DaemonParameters;
import org.gradle.tooling.internal.provider.connection.ProviderOperationParameters;

public class ConnectionOperationParameters {
    private final DaemonParameters daemonParameters;
    private final ProviderOperationParameters operationParameters;

    public ConnectionOperationParameters(DaemonParameters daemonParameters, ProviderOperationParameters operationParameters) {
        this.daemonParameters = daemonParameters;
        this.operationParameters = operationParameters;
    }

    public DaemonParameters getDaemonParameters() {
        return daemonParameters;
    }

    public ProviderOperationParameters getOperationParameters() {
        return operationParameters;
    }
}
