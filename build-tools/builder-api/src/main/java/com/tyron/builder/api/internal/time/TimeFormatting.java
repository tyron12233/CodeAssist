package com.tyron.builder.api.internal.time;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class TimeFormatting {

    private static final int MILLIS_PER_SECOND = 1000;
    private static final int MILLIS_PER_MINUTE = 60 * MILLIS_PER_SECOND;
    private static final int MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE;
    private static final int MILLIS_PER_DAY = 24 * MILLIS_PER_HOUR;

    private TimeFormatting() {
    }

    public static String formatDurationVerbose(long durationMillis) {
        StringBuilder result = new StringBuilder();
        if (durationMillis > MILLIS_PER_HOUR) {
            result.append(durationMillis / MILLIS_PER_HOUR).append(" hrs ");
        }
        if (durationMillis > (long) MILLIS_PER_MINUTE) {
            result.append((durationMillis % MILLIS_PER_HOUR) / MILLIS_PER_MINUTE).append(" mins ");
        }
        result.append((durationMillis % MILLIS_PER_MINUTE) / 1000.0).append(" secs");
        return result.toString();
    }

    public static String formatDurationTerse(long elapsedTimeInMs) {
        StringBuilder result = new StringBuilder();
        if (elapsedTimeInMs >= MILLIS_PER_HOUR) {
            result.append(elapsedTimeInMs / MILLIS_PER_HOUR).append("h ");
        }
        if (elapsedTimeInMs >= MILLIS_PER_MINUTE) {
            long hours = (elapsedTimeInMs % MILLIS_PER_HOUR) / MILLIS_PER_MINUTE;
            if (hours > 0) {
                result.append(hours).append("m ");
            }
        }
        if (elapsedTimeInMs >= MILLIS_PER_SECOND) {
            long seconds = (elapsedTimeInMs % MILLIS_PER_MINUTE) / 1000;
            if (seconds > 0) {
                result.append(seconds).append("s");
            }
        } else {
            result.append(elapsedTimeInMs).append("ms");
        }
        return result.toString().trim();
    }

    public static String formatDurationVeryTerse(long duration) {
        if (duration == 0) {
            return "0s";
        }

        StringBuilder result = new StringBuilder();

        long days = duration / MILLIS_PER_DAY;
        duration = duration % MILLIS_PER_DAY;
        if (days > 0) {
            result.append(days);
            result.append("d");
        }
        long hours = duration / MILLIS_PER_HOUR;
        duration = duration % MILLIS_PER_HOUR;
        if (hours > 0 || result.length() > 0) {
            result.append(hours);
            result.append("h");
        }
        long minutes = duration / MILLIS_PER_MINUTE;
        duration = duration % MILLIS_PER_MINUTE;
        if (minutes > 0 || result.length() > 0) {
            result.append(minutes);
            result.append("m");
        }
        int secondsScale = result.length() > 0 ? 2 : 3;
        result.append(BigDecimal.valueOf(duration).divide(BigDecimal.valueOf(MILLIS_PER_SECOND)).setScale(secondsScale, RoundingMode.HALF_UP));
        result.append("s");
        return result.toString();
    }

}