package org.gradle.internal.time;


public interface CountdownTimer extends Timer {

    boolean hasExpired();

    long getRemainingMillis();

    long getTimeoutMillis();

}