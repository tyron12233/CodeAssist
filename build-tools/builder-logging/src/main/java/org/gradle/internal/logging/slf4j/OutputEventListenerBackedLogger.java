package org.gradle.internal.logging.slf4j;

import org.gradle.internal.logging.events.OutputEventListener;
import org.gradle.internal.operations.OperationIdentifier;
import org.gradle.internal.time.Clock;
import org.gradle.api.logging.LogLevel;
import org.gradle.internal.logging.events.LogEvent;


public class OutputEventListenerBackedLogger extends BuildOperationAwareLogger {

    private final String name;
    private final OutputEventListenerBackedLoggerContext context;
    private final Clock clock;

    public OutputEventListenerBackedLogger(String name, OutputEventListenerBackedLoggerContext context, Clock clock) {
        this.name = name;
        this.context = context;
        this.clock = clock;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    boolean isLevelAtMost(LogLevel levelLimit) {
        return levelLimit.compareTo(context.getLevel()) >= 0;
    }

    @Override
    void log(LogLevel logLevel, Throwable throwable, String message, OperationIdentifier operationIdentifier) {
        LogEvent logEvent = new LogEvent(clock.getCurrentTime(), name, logLevel, message, throwable, operationIdentifier);
        OutputEventListener outputEventListener = context.getOutputEventListener();
        try {
            outputEventListener.onOutput(logEvent);
        } catch (Throwable e) {
            // fall back to standard out
            e.printStackTrace(System.out);
        }
    }
}
