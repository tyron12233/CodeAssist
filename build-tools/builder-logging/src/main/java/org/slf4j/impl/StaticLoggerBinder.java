package org.slf4j.impl;

import com.tyron.builder.internal.time.Time;
import com.tyron.builder.internal.logging.slf4j.OutputEventListenerBackedLoggerContext;

import org.slf4j.ILoggerFactory;
import org.slf4j.spi.LoggerFactoryBinder;

public class StaticLoggerBinder implements LoggerFactoryBinder {

    private static final StaticLoggerBinder SINGLETON = new StaticLoggerBinder();
    private static final String LOGGER_FACTORY_CLASS_STR = OutputEventListenerBackedLoggerContext.class.getName();

    private final OutputEventListenerBackedLoggerContext factory = new OutputEventListenerBackedLoggerContext(Time.clock());

    public static StaticLoggerBinder getSingleton() {
        return SINGLETON;
    }

    /**
     * Declare the version of the SLF4J API this implementation is compiled against.
     * The value of this field is usually modified with each release.
     */
    public static final String REQUESTED_API_VERSION = "1.7.28";

    @Override
    public ILoggerFactory getLoggerFactory() {
        return factory;
    }

    @Override
    public String getLoggerFactoryClassStr() {
        return LOGGER_FACTORY_CLASS_STR;
    }
}
