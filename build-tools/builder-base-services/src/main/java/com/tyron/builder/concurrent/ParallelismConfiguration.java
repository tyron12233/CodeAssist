package com.tyron.builder.concurrent;


/**
 * A {@code ParallelismConfiguration} defines the parallel settings for a Gradle build.
 *
 * @since 4.1
 */
public interface ParallelismConfiguration {
    /**
     * Returns true if parallel project execution is enabled.
     *
     * @see #getMaxWorkerCount()
     */
    boolean isParallelProjectExecutionEnabled();

    /**
     * Enables/disables parallel project execution.
     *
     * @see #isParallelProjectExecutionEnabled()
     */
    void setParallelProjectExecutionEnabled(boolean parallelProjectExecution);

    /**
     * Returns the maximum number of concurrent workers used for underlying build operations.
     *
     * Workers can be threads, processes or whatever Gradle considers a "worker". Some examples:
     *
     * <ul>
     *     <li>A thread running a task</li>
     *     <li>A test process</li>
     *     <li>A language compiler in a forked process</li>
     * </ul>
     *
     * Defaults to the number of processors available to the Java virtual machine.
     *
     * @return maximum number of concurrent workers, always &gt;= 1.
     * @see java.lang.Runtime#availableProcessors()
     */
    int getMaxWorkerCount();

    /**
     * Specifies the maximum number of concurrent workers used for underlying build operations.
     *
     * @throws IllegalArgumentException if {@code maxWorkerCount} is &lt; 1
     * @see #getMaxWorkerCount()
     */
    void setMaxWorkerCount(int maxWorkerCount);

}