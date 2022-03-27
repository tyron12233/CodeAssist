package com.tyron.builder.api.internal.time;


public interface CountdownTimer extends Timer {

    boolean hasExpired();

    long getRemainingMillis();

    long getTimeoutMillis();

}