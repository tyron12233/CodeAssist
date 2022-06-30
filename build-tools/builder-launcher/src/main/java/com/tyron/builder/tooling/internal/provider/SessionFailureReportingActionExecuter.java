package com.tyron.builder.tooling.internal.provider;

import com.tyron.builder.BuildResult;
import com.tyron.builder.api.logging.Logging;
import com.tyron.builder.api.logging.configuration.ShowStacktrace;
import com.tyron.builder.execution.WorkValidationWarningReporter;
import com.tyron.builder.initialization.BuildRequestContext;
import com.tyron.builder.initialization.exception.DefaultExceptionAnalyser;
import com.tyron.builder.initialization.exception.ExceptionAnalyser;
import com.tyron.builder.initialization.exception.MultipleBuildFailuresExceptionAnalyser;
import com.tyron.builder.initialization.exception.StackTraceSanitizingExceptionAnalyser;
import com.tyron.builder.internal.buildevents.BuildLogger;
import com.tyron.builder.internal.buildevents.BuildStartedTime;
import com.tyron.builder.internal.event.DefaultListenerManager;
import com.tyron.builder.internal.invocation.BuildAction;
import com.tyron.builder.internal.logging.text.StyledTextOutputFactory;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.time.Clock;
import com.tyron.builder.launcher.exec.BuildActionExecuter;
import com.tyron.builder.launcher.exec.BuildActionParameters;
import com.tyron.builder.launcher.exec.BuildActionResult;

/**
 * Reports any unreported failure that causes the session to finish.
 */
public class SessionFailureReportingActionExecuter implements BuildActionExecuter<BuildActionParameters, BuildRequestContext> {
    private final BuildActionExecuter<BuildActionParameters, BuildRequestContext> delegate;
    private final StyledTextOutputFactory styledTextOutputFactory;
    private final Clock clock;
    private final WorkValidationWarningReporter workValidationWarningReporter;

    public SessionFailureReportingActionExecuter(StyledTextOutputFactory styledTextOutputFactory, Clock clock, WorkValidationWarningReporter workValidationWarningReporter, BuildActionExecuter<BuildActionParameters, BuildRequestContext> delegate) {
        this.styledTextOutputFactory = styledTextOutputFactory;
        this.clock = clock;
        this.workValidationWarningReporter = workValidationWarningReporter;
        this.delegate = delegate;
    }

    @Override
    public BuildActionResult execute(BuildAction action, BuildActionParameters actionParameters, BuildRequestContext requestContext) {
        try {
            return delegate.execute(action, actionParameters, requestContext);
        } catch (Throwable e) {
            // TODO - wire this stuff in properly

            // Sanitise the exception and report it
            ExceptionAnalyser exceptionAnalyser = new MultipleBuildFailuresExceptionAnalyser(new DefaultExceptionAnalyser(new DefaultListenerManager(Scopes.BuildSession.class)));
            if (action.getStartParameter().getShowStacktrace() != ShowStacktrace.ALWAYS_FULL) {
                exceptionAnalyser = new StackTraceSanitizingExceptionAnalyser(exceptionAnalyser);
            }
            RuntimeException failure = exceptionAnalyser.transform(e);
            BuildStartedTime buildStartedTime = BuildStartedTime.startingAt(requestContext.getStartTime());
            BuildLogger buildLogger = new BuildLogger(
                    Logging.getLogger(BuildSessionLifecycleBuildActionExecuter.class),
                    styledTextOutputFactory,
                    action.getStartParameter(),
                    requestContext,
                    buildStartedTime,
                    clock,
                    workValidationWarningReporter,
                    null
            );
            buildLogger.buildFinished(new BuildResult(null, failure));
            buildLogger.logResult(failure);
            return BuildActionResult.failed(failure);
        }
    }
}
