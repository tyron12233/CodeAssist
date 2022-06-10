package org.gradle.internal.execution.timeout;


/**
 * Represents a timeout for some piece of work.
 */
public interface Timeout {
    /**
     * Stops the timeout and returns whether the work did time out.
     */
    boolean stop();
}