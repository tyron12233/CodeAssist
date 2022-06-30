package com.tyron.builder.internal.resources;

public interface ProjectLockStatistics {
    void measure(Runnable runnable);

    long getTotalWaitTimeMillis();
}