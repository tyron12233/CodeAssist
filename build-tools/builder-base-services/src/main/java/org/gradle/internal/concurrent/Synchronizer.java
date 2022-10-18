package org.gradle.internal.concurrent;

import org.gradle.internal.Factory;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Synchronizer {

    private final Lock lock = new ReentrantLock();

    public <T> T synchronize(Factory<T> factory) {
        lock.lock();
        try {
            return factory.create();
        } finally {
            lock.unlock();
        }
    }

    public void synchronize(Runnable operation) {
        lock.lock();
        try {
            operation.run();
        } finally {
            lock.unlock();
        }
    }
}