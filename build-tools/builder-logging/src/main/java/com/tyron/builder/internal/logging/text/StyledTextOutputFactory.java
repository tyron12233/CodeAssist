package com.tyron.builder.internal.logging.text;


import com.tyron.builder.internal.logging.text.StyledTextOutput;
import com.tyron.builder.api.logging.LogLevel;

public interface StyledTextOutputFactory {
    /**
     * Creates a {@code StyledTextOutput} with the given category and the standard output log level.
     *
     * @param logCategory The log category.
     * @return the output
     */
    StyledTextOutput create(String logCategory);

    /**
     * Creates a {@code StyledTextOutput} with the given category and the standard output log level.
     *
     * @param logCategory The log category.
     * @return the output
     */

    StyledTextOutput create(Class<?> logCategory);

    /**
     * Creates a {@code StyledTextOutput} with the given category and log level.
     *
     * @param logCategory The log category.
     * @param logLevel The log level. Can be null to use the standard output log level.
     * @return the output
     */
    StyledTextOutput create(Class<?> logCategory, LogLevel logLevel);

    /**
     * Creates a {@code StyledTextOutput} with the given category and log level.
     *
     * @param logCategory The log category.
     * @param logLevel The log level. Can be null to use the standard output log level.
     * @return the output
     */
    StyledTextOutput create(String logCategory, LogLevel logLevel);
}