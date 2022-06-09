package com.tyron.builder.launcher.cli;

import com.tyron.builder.api.Action;
import com.tyron.builder.initialization.ReportedException;
import com.tyron.builder.initialization.exception.InitializationException;
import com.tyron.builder.internal.exceptions.ContextAwareException;
import com.tyron.builder.internal.logging.LoggingOutputInternal;
import com.tyron.builder.internal.service.ServiceCreationException;
import com.tyron.builder.launcher.bootstrap.ExecutionListener;

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
