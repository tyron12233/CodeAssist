package com.tyron.completion.progress;

/**
 * Thrown when a certain process must be canceled.
 */
public class ProcessCanceledException extends RuntimeException{

    public ProcessCanceledException() {
        super();
    }

    public ProcessCanceledException(Throwable cause) {
        super(cause);
        if (cause instanceof ProcessCanceledException) {
            throw new IllegalArgumentException("Must not self-wrap ProcessCanceledException.");
        }
    }
}
