package org.gradle.internal.resources;

public interface ProjectLockStatistics {
    void measure(Runnable runnable);

    long getTotalWaitTimeMillis();
}