package com.tyron.builder.internal.logging;

import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.api.logging.configuration.ConsoleOutput;
import com.tyron.builder.api.logging.configuration.LoggingConfiguration;
import com.tyron.builder.api.logging.configuration.ShowStacktrace;
import com.tyron.builder.api.logging.configuration.WarningMode;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

import java.io.Serializable;

public class DefaultLoggingConfiguration implements Serializable, LoggingConfiguration {
    private LogLevel logLevel = LogLevel.LIFECYCLE;
    private ShowStacktrace showStacktrace = ShowStacktrace.INTERNAL_EXCEPTIONS;
    private ConsoleOutput consoleOutput = ConsoleOutput.Auto;
    private WarningMode warningMode =  WarningMode.Summary;

    public boolean equals(Object obj) {
        return EqualsBuilder.reflectionEquals(this, obj);
    }

    public int hashCode() {
        return HashCodeBuilder.reflectionHashCode(this);
    }

    @Override
    public LogLevel getLogLevel() {
        return logLevel;
    }

    @Override
    public void setLogLevel(LogLevel logLevel) {
        this.logLevel = logLevel;
    }

    @Override
    public ConsoleOutput getConsoleOutput() {
        return consoleOutput;
    }

    @Override
    public void setConsoleOutput(ConsoleOutput consoleOutput) {
        this.consoleOutput = consoleOutput;
    }

    @Override
    public WarningMode getWarningMode() {
        return warningMode;
    }

    @Override
    public void setWarningMode(WarningMode warningMode) {
        this.warningMode = warningMode;
    }

    @Override
    public ShowStacktrace getShowStacktrace() {
        return showStacktrace;
    }

    @Override
    public void setShowStacktrace(ShowStacktrace showStacktrace) {
        this.showStacktrace = showStacktrace;
    }
}