package com.tyron.builder.internal.exceptions;

import com.tyron.builder.internal.Factory;

/**
 * A specialized version of multi cause exception that is cheaper to create
 * because we avoid to fill a stack trace, and the message MUST be generated lazily.
 */
@Contextual
public class DefaultMultiCauseExceptionNoStackTrace extends DefaultMultiCauseException {
    public DefaultMultiCauseExceptionNoStackTrace(Factory<String> messageFactory) {
        super(messageFactory);
    }

    public DefaultMultiCauseExceptionNoStackTrace(Factory<String> messageFactory, Throwable... causes) {
        super(messageFactory, causes);
    }

    public DefaultMultiCauseExceptionNoStackTrace(Factory<String> messageFactory, Iterable<? extends Throwable> causes) {
        super(messageFactory, causes);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
