package com.tyron.builder.api.internal.concurrent;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Provides meaningful names to threads created in a thread pool.
 */
public class ThreadFactoryImpl implements ThreadFactory {
    private final AtomicLong counter = new AtomicLong();
    private final String displayName;
    @Nullable
    private final ClassLoader contextClassloader;

    public ThreadFactoryImpl(String displayName, @Nullable ClassLoader contextClassloader) {
        this.displayName = displayName;
        this.contextClassloader = contextClassloader;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread thread = new Thread(r);
        thread.setName(nextThreadName());
        thread.setContextClassLoader(contextClassloader);
        return thread;
    }

    private String nextThreadName() {
        long count = counter.incrementAndGet();
        return count == 1 ? displayName : displayName + " Thread " + count;
    }
}