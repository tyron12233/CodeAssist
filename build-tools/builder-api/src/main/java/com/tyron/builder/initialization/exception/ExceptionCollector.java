package com.tyron.builder.initialization.exception;

import java.util.Collection;

public interface ExceptionCollector {
    /**
     * Transforms the given failure into zero or more (most likely 1) failures to be reported.
     */
    void collectFailures(Throwable exception, Collection<? super Throwable> failures);
}