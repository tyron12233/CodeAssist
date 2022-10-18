package org.gradle.internal.logging.text;

import org.gradle.internal.logging.text.StyledTextOutput;
import org.gradle.api.logging.LogLevel;

public abstract class AbstractStyledTextOutputFactory implements StyledTextOutputFactory {
    @Override
    public StyledTextOutput create(Class<?> logCategory) {
        return create(logCategory.getName());
    }

    @Override
    public StyledTextOutput create(String logCategory) {
        return create(logCategory, null);
    }

    @Override
    public StyledTextOutput create(Class<?> logCategory, LogLevel logLevel) {
        return create(logCategory.getName(), logLevel);
    }
}