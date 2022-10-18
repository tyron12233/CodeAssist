package org.gradle.launcher.cli;

import org.gradle.api.Action;
import org.gradle.initialization.ReportedException;
import org.gradle.initialization.exception.InitializationException;
import org.gradle.internal.exceptions.ContextAwareException;
import org.gradle.internal.logging.LoggingOutputInternal;
import org.gradle.internal.service.ServiceCreationException;
import org.gradle.launcher.bootstrap.ExecutionListener;

public class ExceptionReportingAction implements Action<ExecutionListener> {
    private final Action<ExecutionListener> action;
    private final Action<Throwable> reporter;
    private final LoggingOutputInternal loggingOutput;

    public ExceptionReportingAction(Action<Throwable> reporter, LoggingOutputInternal loggingOutput, Action<ExecutionListener> action) {
        this.action = action;
        this.reporter = reporter;
        this.loggingOutput = loggingOutput;
    }

    @Override
    public void execute(ExecutionListener executionListener) {
        try {
            try {
                action.execute(executionListener);
            } finally {
                loggingOutput.flush();
            }
        } catch (ReportedException e) {
            // Exception has already been reported
            executionListener.onFailure(e);
        } catch (ServiceCreationException e) {
            reporter.execute(new ContextAwareException(new InitializationException(e)));
            executionListener.onFailure(e);
        } catch (Throwable t) {
            reporter.execute(t);
            executionListener.onFailure(t);
        }
    }
}
