package com.tyron.builder.internal.logging;

public interface StandardOutputCapture {

    /**
     * Starts redirection of System.out and System.err to the Gradle logging system.
     *
     * @return this
     */
    StandardOutputCapture start();

    /**
     * Restores System.out and System.err to the values they had before {@link #start()} has been called.
     *
     * @return this
     */
    StandardOutputCapture stop();
}
