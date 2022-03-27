package com.tyron.builder.api.internal.time;

public interface Timer {

    /**
     * @return A human-consumable description of the elapsed time.
     */
    String getElapsed();

    /**
     * Return the elapsed time in ms. Returned value is always &gt;= 0.
     * @return The elapsed time, in ms.
     */
    long getElapsedMillis();

    /**
     * Restart this timer. Sets elapsed time to zero.
     */
    void reset();
}