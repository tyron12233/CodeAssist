package com.tyron.builder.api.internal.time;

import java.util.concurrent.TimeUnit;

class DefaultCountdownTimer extends DefaultTimer implements CountdownTimer {

    private final long timeoutMillis;

    DefaultCountdownTimer(TimeSource timeSource, long timeout, TimeUnit unit) {
        super(timeSource);
        if (timeout <= 0) {
            throw new IllegalArgumentException();
        }
        this.timeoutMillis = unit.toMillis(timeout);
    }

    @Override
    public boolean hasExpired() {
        return getRemainingMillis() <= 0;
    }

    @Override
    public long getRemainingMillis() {
        return Math.max(timeoutMillis - getElapsedMillis(), 0);
    }

    @Override
    public long getTimeoutMillis() {
        return timeoutMillis;
    }
}