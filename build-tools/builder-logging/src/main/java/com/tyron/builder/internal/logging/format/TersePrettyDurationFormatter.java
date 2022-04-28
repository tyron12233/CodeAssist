package com.tyron.builder.internal.logging.format;

import com.tyron.builder.internal.logging.format.DurationFormatter;
import com.tyron.builder.internal.time.TimeFormatting;

public class TersePrettyDurationFormatter implements DurationFormatter {

    @Override
    public String format(long elapsedTimeInMs) {
        return TimeFormatting.formatDurationTerse(elapsedTimeInMs);
    }
}