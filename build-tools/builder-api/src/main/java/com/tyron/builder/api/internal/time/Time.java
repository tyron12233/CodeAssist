package com.tyron.builder.api.internal.time;

import java.util.concurrent.TimeUnit;

/**
 * Instruments for observing time.
 */
public abstract class Time {

    private static final Clock CLOCK = new MonotonicClock();

    /**
     * A clock that is guaranteed not to go backwards.
     *
     * This should generally be used by Gradle processes instead of System.currentTimeMillis().
     * For the gory details, see {@link MonotonicClock}.
     *
     * For timing activities, where correlation with the current time is not required, use {@link #startTimer()}.
     */
    public static Clock clock() {
        return CLOCK;
    }

    /**
     * Replacement for System.currentTimeMillis(), based on {@link #clock()}.
     */
    public static long currentTimeMillis() {
        return CLOCK.getCurrentTime();
    }

    /**
     * Measures elapsed time.
     *
     * Timers use System.nanoTime() to measure elapsed time,
     * and are therefore not synchronized with {@link #clock()} or the system wall clock.
     *
     * System.nanoTime() does not consider time elapsed while the system is in hibernation.
     * Therefore, timers effectively measure the elapsed time, of which the system was awake.
     */
    public static Timer  startTimer() {
        return new DefaultTimer(TimeSource.SYSTEM);
    }

    public static CountdownTimer startCountdownTimer(long timeoutMillis) {
        return new DefaultCountdownTimer(TimeSource.SYSTEM, timeoutMillis, TimeUnit.MILLISECONDS);
    }

    public static CountdownTimer startCountdownTimer(long timeout, TimeUnit unit) {
        return new DefaultCountdownTimer(TimeSource.SYSTEM, timeout, unit);
    }

    private Time() {
    }

}