package com.tyron.builder.api.logging;

/**
 * <p>A {@code StandardOutputListener} receives text written by Gradle's logging system to standard output or
 * error.</p>
 */
public interface StandardOutputListener {
    /**
     * Called when some output is written by the logging system.
     *
     * @param output The text.
     */
    void onOutput(CharSequence output);
}