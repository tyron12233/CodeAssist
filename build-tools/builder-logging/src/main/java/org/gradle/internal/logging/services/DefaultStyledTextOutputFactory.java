package org.gradle.internal.logging.services;


import org.gradle.internal.logging.text.StyledTextOutput;
import org.gradle.internal.logging.events.OutputEventListener;
import org.gradle.internal.logging.text.AbstractStyledTextOutputFactory;
import org.gradle.internal.logging.text.StyledTextOutputFactory;
import org.gradle.internal.time.Clock;
import org.gradle.api.logging.LogLevel;

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