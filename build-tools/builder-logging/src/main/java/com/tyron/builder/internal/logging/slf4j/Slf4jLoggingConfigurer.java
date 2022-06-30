package com.tyron.builder.internal.logging.slf4j;

import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.internal.logging.config.LoggingConfigurer;

import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/**
 * A {@link LoggingConfigurer} implementation which configures custom slf4j binding to route logging events to a provided {@link
 * OutputEventListener}.
 */
public class Slf4jLoggingConfigurer implements LoggingConfigurer {
    private final OutputEventListener outputEventListener;

    private LogLevel currentLevel;

    public Slf4jLoggingConfigurer(OutputEventListener outputListener) {
        outputEventListener = outputListener;
    }

    @Override
    public void configure(LogLevel logLevel) {
        if (logLevel == currentLevel) {
            return;
        }

        ILoggerFactory loggerFactory = LoggerFactory.getILoggerFactory();
        if (!(loggerFactory instanceof OutputEventListenerBackedLoggerContext)) {
            // Cannot configure Slf4j logger. This will happen if:
            // - Tests are executed with a custom classloader (e.g using `java.system.class.loader`)
            // - Tests are run with `--module-path`, effectively hiding Gradle classes
            return;
        }
        OutputEventListenerBackedLoggerContext context = (OutputEventListenerBackedLoggerContext) loggerFactory;

        if (currentLevel == null) {
            context.setOutputEventListener(outputEventListener);
        }

        currentLevel = logLevel;
        context.setLevel(logLevel);
    }
}
