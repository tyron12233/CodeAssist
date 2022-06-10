package org.gradle.internal.logging.events;

import org.gradle.internal.logging.text.StyledTextOutput;
import org.gradle.internal.logging.events.LogLevelConverter;
import org.gradle.internal.logging.events.RenderableOutputEvent;
import org.gradle.internal.operations.OperationIdentifier;
import org.gradle.api.logging.LogLevel;
import org.gradle.internal.logging.events.operations.LogEventBuildOperationProgressDetails;
import org.gradle.internal.operations.logging.LogEventLevel;

import javax.annotation.Nullable;

@SuppressWarnings("deprecation")
public class LogEvent extends RenderableOutputEvent implements LogEventBuildOperationProgressDetails {
    private final String message;
    private final Throwable throwable;

    public LogEvent(long timestamp, String category, LogLevel logLevel, String message, @Nullable Throwable throwable) {
        this(timestamp, category, logLevel, message, throwable, null);
    }

    public LogEvent(long timestamp, String category, LogLevel logLevel, String message, @Nullable Throwable throwable, @Nullable OperationIdentifier buildOperationIdentifier) {
        super(timestamp, category, logLevel, buildOperationIdentifier);
        this.message = message;
        this.throwable = throwable;
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    @Nullable
    public Throwable getThrowable() {
        return throwable;
    }

    @Override
    public void render(StyledTextOutput output) {
        output.text(message);
        output.println();
        if (throwable != null) {
            output.exception(throwable);
        }
    }

    @Override
    public String toString() {
        return "[" + getLogLevel() + "] [" + getCategory() + "] " + message;
    }

    @Override
    public LogEventLevel getLevel() {
        return LogLevelConverter.convert(getLogLevel());
    }

    @Override
    public RenderableOutputEvent withBuildOperationId(OperationIdentifier buildOperationId) {
        return new LogEvent(getTimestamp(), getCategory(), getLogLevel(), message, throwable, buildOperationId);
    }
}

