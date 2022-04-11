package com.tyron.builder.api.internal.exceptions;

public interface DiagnosticsVisitor {
    /**
     * Adds the description of some candidate.
     */
    DiagnosticsVisitor candidate(String displayName);

    /**
     * Adds an example for the previous candidate. Can have multiple examples.
     */
    DiagnosticsVisitor example(String example);

    /**
     * Adds a set of potential values for the previous candidate, if known.
     */
    DiagnosticsVisitor values(Iterable<?> values);
}