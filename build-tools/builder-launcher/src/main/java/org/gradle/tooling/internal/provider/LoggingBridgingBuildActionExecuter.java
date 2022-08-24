package org.gradle.tooling.internal.provider;

import org.gradle.api.logging.configuration.ConsoleOutput;
import org.gradle.initialization.BuildRequestContext;
import org.gradle.internal.invocation.BuildAction;
import org.gradle.internal.logging.LoggingManagerInternal;
import org.gradle.internal.logging.events.OutputEvent;
import org.gradle.internal.logging.events.OutputEventListener;
import org.gradle.internal.logging.events.ProgressCompleteEvent;
import org.gradle.internal.logging.events.ProgressStartEvent;
import org.gradle.launcher.exec.BuildActionExecuter;
import org.gradle.launcher.exec.BuildActionResult;
import org.gradle.tooling.internal.protocol.ProgressListenerVersion1;
import org.gradle.tooling.internal.provider.connection.ProviderOperationParameters;

import org.apache.commons.io.output.NullOutputStream;

import java.io.OutputStream;

/**
 * A {@link org.gradle.launcher.exec.BuildActionExecuter} which routes Gradle logging to those listeners specified in the {@link ProviderOperationParameters} provided with a tooling api build request.
 */
public class LoggingBridgingBuildActionExecuter implements BuildActionExecuter<ConnectionOperationParameters, BuildRequestContext> {
    private final LoggingManagerInternal loggingManager;
    private final BuildActionExecuter<ConnectionOperationParameters, BuildRequestContext> executer;

    public LoggingBridgingBuildActionExecuter(BuildActionExecuter<ConnectionOperationParameters, BuildRequestContext> executer, LoggingManagerInternal loggingManager) {
        this.executer = executer;
        this.loggingManager = loggingManager;
    }

    @Override
    public BuildActionResult execute(BuildAction action, ConnectionOperationParameters parameters, BuildRequestContext buildRequestContext) {
        ProviderOperationParameters actionParameters = parameters.getOperationParameters();
        if (Boolean.TRUE.equals(actionParameters.isColorOutput()) && actionParameters.getStandardOutput() != null) {
            loggingManager.attachConsole(actionParameters.getStandardOutput(), notNull(actionParameters.getStandardError()), ConsoleOutput.Rich);
        } else if (actionParameters.getStandardOutput() != null || actionParameters.getStandardError() != null) {
            loggingManager.attachConsole(notNull(actionParameters.getStandardOutput()), notNull(actionParameters.getStandardError()), ConsoleOutput.Plain);
        }
        ProgressListenerVersion1 progressListener = actionParameters.getProgressListener();
        OutputEventListenerAdapter listener = new OutputEventListenerAdapter(progressListener);
        loggingManager.addOutputEventListener(listener);
        loggingManager.setLevelInternal(actionParameters.getBuildLogLevel());
        loggingManager.start();
        try {
            return executer.execute(action, parameters, buildRequestContext);
        } finally {
            loggingManager.stop();
        }
    }

    private OutputStream notNull(OutputStream outputStream) {
        if (outputStream == null) {
            return NullOutputStream.NULL_OUTPUT_STREAM;
        }
        return outputStream;
    }

    private static class OutputEventListenerAdapter implements OutputEventListener {
        private final ProgressListenerVersion1 progressListener;

        public OutputEventListenerAdapter(ProgressListenerVersion1 progressListener) {
            this.progressListener = progressListener;
        }

        @Override
        public void onOutput(OutputEvent event) {
            if (event instanceof ProgressStartEvent) {
                ProgressStartEvent startEvent = (ProgressStartEvent) event;
                progressListener.onOperationStart(startEvent.getDescription());
            } else if (event instanceof ProgressCompleteEvent) {
                progressListener.onOperationEnd();
            }
        }
    }
}