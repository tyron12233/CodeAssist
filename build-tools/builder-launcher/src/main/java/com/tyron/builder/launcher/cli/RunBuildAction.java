package com.tyron.builder.launcher.cli;

import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.initialization.BuildClientMetaData;
import com.tyron.builder.initialization.BuildRequestContext;
import com.tyron.builder.initialization.DefaultBuildCancellationToken;
import com.tyron.builder.initialization.DefaultBuildRequestContext;
import com.tyron.builder.initialization.DefaultBuildRequestMetaData;
import com.tyron.builder.initialization.NoOpBuildEventConsumer;
import com.tyron.builder.initialization.ReportedException;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.concurrent.Stoppable;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.launcher.exec.BuildActionExecuter;
import com.tyron.builder.launcher.exec.BuildActionParameters;
import com.tyron.builder.launcher.exec.BuildActionResult;
import com.tyron.builder.tooling.internal.provider.action.ExecuteBuildAction;
import com.tyron.builder.tooling.internal.provider.serialization.PayloadSerializer;
import com.tyron.builder.tooling.internal.provider.serialization.SerializedPayload;

public class RunBuildAction implements Runnable {
    private final BuildActionExecuter<BuildActionParameters, BuildRequestContext> executer;
    private final StartParameterInternal startParameter;
    private final BuildClientMetaData clientMetaData;
    private final long startTime;
    private final BuildActionParameters buildActionParameters;
    private final ServiceRegistry sharedServices;
    private final Stoppable stoppable;

    public RunBuildAction(BuildActionExecuter<BuildActionParameters, BuildRequestContext> executer, StartParameterInternal startParameter, BuildClientMetaData clientMetaData, long startTime,
                          BuildActionParameters buildActionParameters, ServiceRegistry sharedServices, Stoppable stoppable) {
        this.executer = executer;
        this.startParameter = startParameter;
        this.clientMetaData = clientMetaData;
        this.startTime = startTime;
        this.buildActionParameters = buildActionParameters;
        this.sharedServices = sharedServices;
        this.stoppable = stoppable;
    }

    @Override
    public void run() {
        try {
            boolean isConsoleOutput = true; //sharedServices.get(ConsoleDetector.class).isConsoleInput())

            BuildActionResult result = executer.execute(
                new ExecuteBuildAction(startParameter),
                buildActionParameters,
                new DefaultBuildRequestContext(new DefaultBuildRequestMetaData(clientMetaData, startTime, isConsoleOutput), new DefaultBuildCancellationToken(), new NoOpBuildEventConsumer())
            );
            if (result.hasFailure()) {
                // Don't need to unpack the serialized failure. It will already have been reported and is not used by anything downstream of this action.
                throw new ReportedException();
            }
        } finally {
            if (stoppable != null) {
                stoppable.stop();
            }
        }
    }
}
