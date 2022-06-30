package com.tyron.builder.internal.logging.services;


import com.tyron.builder.internal.logging.text.StyledTextOutput;
import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.internal.logging.text.AbstractStyledTextOutputFactory;
import com.tyron.builder.internal.logging.text.StyledTextOutputFactory;
import com.tyron.builder.internal.time.Clock;
import com.tyron.builder.api.logging.LogLevel;

public class DefaultStyledTextOutputFactory extends AbstractStyledTextOutputFactory implements StyledTextOutputFactory {
    private final OutputEventListener outputEventListener;
    private final Clock clock;

    public DefaultStyledTextOutputFactory(OutputEventListener outputEventListener, Clock clock) {
        this.outputEventListener = outputEventListener;
        this.clock = clock;
    }

    @Override
    public StyledTextOutput create(String logCategory, LogLevel logLevel) {
        return new LoggingBackedStyledTextOutput(outputEventListener, logCategory, logLevel, clock);
    }
}