package org.gradle.tooling.internal.provider;

import org.gradle.BuildResult;
import org.gradle.api.logging.Logging;
import org.gradle.api.logging.configuration.ShowStacktrace;
import org.gradle.execution.WorkValidationWarningReporter;
import org.gradle.initialization.BuildRequestContext;
import org.gradle.initialization.exception.DefaultExceptionAnalyser;
import org.gradle.initialization.exception.ExceptionAnalyser;
import org.gradle.initialization.exception.MultipleBuildFailuresExceptionAnalyser;
import org.gradle.initialization.exception.StackTraceSanitizingExceptionAnalyser;
import org.gradle.internal.buildevents.BuildLogger;
import org.gradle.internal.buildevents.BuildStartedTime;
import org.gradle.internal.event.DefaultListenerManager;
import org.gradle.internal.invocation.BuildAction;
import org.gradle.internal.logging.text.StyledTextOutputFactory;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.time.Clock;
import org.gradle.launcher.exec.BuildActionExecuter;
import org.gradle.launcher.exec.BuildActionParameters;
import org.gradle.launcher.exec.BuildActionResult;

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
