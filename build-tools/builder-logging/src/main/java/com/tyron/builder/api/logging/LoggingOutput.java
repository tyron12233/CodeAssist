package com.tyron.builder.api.logging;


/**
 * Provides access to the output of the Gradle logging system.
 */
public interface LoggingOutput {
    /**
     * Adds a listener which receives output written to standard output by the Gradle logging system.
     *
     * @param listener The listener to add.
     */
    void addStandardOutputListener(StandardOutputListener listener);

    /**
     * Removes a listener from standard output.
     *
     * @param listener The listener to remove.
     */
    void removeStandardOutputListener(StandardOutputListener listener);

    /**
     * Adds a listener which receives output written to standard error by the Gradle logging system.
     *
     * @param listener The listener to add.
     */
    void addStandardErrorListener(StandardOutputListener listener);

    /**
     * Removes a listener from standard error.
     *
     * @param listener The listener to remove.
     */
    void removeStandardErrorListener(StandardOutputListener listener);
}
