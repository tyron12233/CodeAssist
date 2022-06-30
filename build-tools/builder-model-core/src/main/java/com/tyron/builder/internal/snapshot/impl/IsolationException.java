package com.tyron.builder.internal.snapshot.impl;

import com.tyron.builder.internal.exceptions.Contextual;
import com.tyron.builder.internal.logging.text.TreeFormatter;

/**
 * Represents a problem while attempting to isolate an instance.
 */
@Contextual
public class IsolationException extends RuntimeException {
    public IsolationException(Object value) {
        super(format(value));
    }

    public IsolationException(Object value, Throwable cause) {
        super(format(value), cause);
    }

    private static String format(Object value) {
        TreeFormatter formatter = new TreeFormatter();
        formatter.node("Could not isolate value ");
        formatter.appendValue(value);
        formatter.append(" of type ");
        formatter.appendType(value.getClass());
        return formatter.toString();
    }
}
