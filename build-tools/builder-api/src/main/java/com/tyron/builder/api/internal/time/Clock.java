package com.tyron.builder.api.internal.time;

/**
 * A device for obtaining the current time.
 *
 * @see Time#clock()
 */
public interface Clock {

    /**
     * The current time in millis.
     */
    long getCurrentTime();

}