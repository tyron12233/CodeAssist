package com.tyron.builder.internal.concurrent;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Wraps a runnable so that it can be interrupted. Useful when {@link java.util.concurrent.Future} is not available or its behavior is not desired.
 *
 * In contrast to {@link java.util.concurrent.Future#cancel(boolean)}, the delegate Runnable is always called, even if an interrupt was received before this runnable was started.
 * This can be used to implement guaranteed delivery mechanisms, where proper interrupt handling is up to the delegate.
 */
public final class InterruptibleRunnable implements Runnable {
    private final Lock stateLock = new ReentrantLock();
    private final Runnable delegate;
    private boolean interrupted;
    private Thread thread;

    public InterruptibleRunnable(Runnable delegate) {
        this.delegate = delegate;
    }

    @Override
    public void run() {
        beforeRun();
        try {
            delegate.run();
        } finally {
            afterRun();
        }
    }

    private void beforeRun() {
        stateLock.lock();
        try {
            thread = Thread.currentThread();
            if (interrupted) {
                thread.interrupt();
            }
        } finally {
            stateLock.unlock();
        }
    }

    private void afterRun() {
        stateLock.lock();
        try {
            Thread.interrupted();
            thread = null;
        } finally {
            stateLock.unlock();
        }
    }

    public void interrupt() {
        stateLock.lock();
        try {
            if (thread == null) {
                interrupted = true;
            } else {
                thread.interrupt();
            }
        } finally {
            stateLock.unlock();
        }
    }
}
