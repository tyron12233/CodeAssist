package org.gradle.internal.logging.format;

import org.gradle.internal.logging.format.DurationFormatter;
import org.gradle.internal.time.TimeFormatting;

public class TersePrettyDurationFormatter implements DurationFormatter {

    @Override
    public String format(long elapsedTimeInMs) {
        return TimeFormatting.formatDurationTerse(elapsedTimeInMs);
    }
}