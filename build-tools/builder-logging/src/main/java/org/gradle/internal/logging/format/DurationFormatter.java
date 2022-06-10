package org.gradle.internal.logging.format;

public interface DurationFormatter {
    /**
     * Given a duration in milliseconds, return a textual representation.
     *
     * @param durationMillis milliseconds duration
     * @return String for display
     */
    String format(long durationMillis);
}
