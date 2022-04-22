package com.tyron.builder.api.logging;

import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * <p>The main entry point for Gradle's logging system. Gradle routes all logging via SLF4J. You can use either an SLF4J
 * {@link org.slf4j.Logger} or a Gradle {@link Logger} to perform logging.</p>
 */
public class Logging {
    public static final Marker LIFECYCLE = MarkerFactory.getDetachedMarker("LIFECYCLE");
    public static final Marker QUIET = MarkerFactory.getDetachedMarker("QUIET");

    /**
     * Returns the logger for the given class.
     *
     * @param c the class.
     * @return the logger. Never returns null.
     */
    @SuppressWarnings("rawtypes")
    public static Logger getLogger(Class c) {
        return (Logger) LoggerFactory.getLogger(c);
    }

    /**
     * Returns the logger with the given name.
     *
     * @param name the logger name.
     * @return the logger. Never returns null.
     */
    public static Logger getLogger(String name) {
        return (Logger) LoggerFactory.getLogger(name);
    }
}