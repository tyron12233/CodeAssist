package com.tyron.builder.api.internal.resources;

public interface ProjectLockStatistics {
    void measure(Runnable runnable);

    long getTotalWaitTimeMillis();
}