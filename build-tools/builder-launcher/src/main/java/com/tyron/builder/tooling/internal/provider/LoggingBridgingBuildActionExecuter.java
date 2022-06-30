//package com.tyron.builder.tooling.internal.provider;
//
//import com.tyron.builder.api.logging.configuration.ConsoleOutput;
//import com.tyron.builder.initialization.BuildRequestContext;
//import com.tyron.builder.internal.invocation.BuildAction;
//import com.tyron.builder.internal.logging.LoggingManagerInternal;
//import com.tyron.builder.internal.logging.events.OutputEvent;
//import com.tyron.builder.internal.logging.events.OutputEventListener;
//import com.tyron.builder.internal.logging.events.ProgressCompleteEvent;
//import com.tyron.builder.internal.logging.events.ProgressStartEvent;
//import com.tyron.builder.launcher.exec.BuildActionExecuter;
//import com.tyron.builder.launcher.exec.BuildActionResult;
//import com.tyron.builder.tooling.internal.protocol.ProgressListenerVersion1;
//import com.tyron.builder.tooling.internal.provider.connection.ProviderOperationParameters;
//
//import org.apache.commons.io.output.NullOutputStream;
//
//import java.io.OutputStream;
//
///**
// * A {@link com.tyron.builder.launcher.exec.BuildActionExecuter} which routes Gradle logging to those listeners specified in the {@link ProviderOperationParameters} provided with a tooling api build request.
// */
//public class LoggingBridgingBuildActionExecuter implements BuildActionExecuter<ConnectionOperationParameters, BuildRequestContext> {
//    private final LoggingManagerInternal loggingManager;
//    private final BuildActionExecuter<ConnectionOperationParameters, BuildRequestContext> executer;
//
//    public LoggingBridgingBuildActionExecuter(BuildActionExecuter<ConnectionOperationParameters, BuildRequestContext> executer, LoggingManagerInternal loggingManager) {
//        this.executer = executer;
//        this.loggingManager = loggingManager;
//    }
//
//    @Override
//    public BuildActionResult execute(BuildAction action, ConnectionOperationParameters parameters, BuildRequestContext buildRequestContext) {
//        ProviderOperationParameters actionParameters = parameters.getOperationParameters();
//        if (Boolean.TRUE.equals(actionParameters.isColorOutput()) && actionParameters.getStandardOutput() != null) {
//            loggingManager.attachConsole(actionParameters.getStandardOutput(), notNull(actionParameters.getStandardError()), ConsoleOutput.Rich);
//        } else if (actionParameters.getStandardOutput() != null || actionParameters.getStandardError() != null) {
//            loggingManager.attachConsole(notNull(actionParameters.getStandardOutput()), notNull(actionParameters.getStandardError()), ConsoleOutput.Plain);
//        }
//        ProgressListenerVersion1 progressListener = actionParameters.getProgressListener();
//        OutputEventListenerAdapter listener = new OutputEventListenerAdapter(progressListener);
//        loggingManager.addOutputEventListener(listener);
//        loggingManager.setLevelInternal(actionParameters.getBuildLogLevel());
//        loggingManager.start();
//        try {
//            return executer.execute(action, parameters, buildRequestContext);
//        } finally {
//            loggingManager.stop();
//        }
//    }
//
//    private OutputStream notNull(OutputStream outputStream) {
//        if (outputStream == null) {
//            return NullOutputStream.NULL_OUTPUT_STREAM;
//        }
//        return outputStream;
//    }
//
//    private static class OutputEventListenerAdapter implements OutputEventListener {
//        private final ProgressListenerVersion1 progressListener;
//
//        public OutputEventListenerAdapter(ProgressListenerVersion1 progressListener) {
//            this.progressListener = progressListener;
//        }
//
//        @Override
//        public void onOutput(OutputEvent event) {
//            if (event instanceof ProgressStartEvent) {
//                ProgressStartEvent startEvent = (ProgressStartEvent) event;
//                progressListener.onOperationStart(startEvent.getDescription());
//            } else if (event instanceof ProgressCompleteEvent) {
//                progressListener.onOperationEnd();
//            }
//        }
//    }
//}