package com.tyron.builder.api.internal.time;

import java.util.concurrent.TimeUnit;

class DefaultTimer implements Timer {

    private final TimeSource timeSource;
    private long startTime;

    DefaultTimer(TimeSource timeSource) {
        this.timeSource = timeSource;
        reset();
    }

    @Override
    public String getElapsed() {
        long elapsedMillis = getElapsedMillis();
        return TimeFormatting.formatDurationVerbose(elapsedMillis);
    }

    @Override
    public long getElapsedMillis() {
        long elapsedNanos = timeSource.nanoTime() - startTime;
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);

        // System.nanoTime() can go backwards under some circumstances.
        // http://bugs.java.com/bugdatabase/view_bug.do?bug_id=6458294
        // This max() call ensures that we don't return negative durations.
        return Math.max(elapsedMillis, 0);
    }

    @Override
    public void reset() {
        startTime = timeSource.nanoTime();
    }

}