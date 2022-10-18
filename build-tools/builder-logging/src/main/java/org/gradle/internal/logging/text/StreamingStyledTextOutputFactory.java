package org.gradle.internal.logging.text;

import org.gradle.api.logging.LogLevel;

public class StreamingStyledTextOutputFactory extends AbstractStyledTextOutputFactory {
    private final Appendable target;

    public StreamingStyledTextOutputFactory(Appendable target) {
        this.target = target;
    }

    @Override
    public StyledTextOutput create(String logCategory, LogLevel logLevel) {
        return new StreamingStyledTextOutput(target);
    }
}
