package com.tyron.builder.initialization;

/**
 * Wraps an exception which has already been logged, and should not be logged again.
 */
public class ReportedException extends RuntimeException {
    public ReportedException() {
    }

    public ReportedException(Throwable throwable) {
        super(throwable);
    }
}
