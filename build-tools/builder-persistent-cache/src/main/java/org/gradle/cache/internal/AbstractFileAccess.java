package org.gradle.cache.internal;

import org.gradle.internal.Factory;
import org.gradle.cache.FileAccess;
import org.gradle.cache.FileIntegrityViolationException;
import org.gradle.cache.LockTimeoutException;

import java.util.concurrent.Callable;

public abstract class AbstractFileAccess implements FileAccess {
    @Override
    public <T> T readFile(final Callable<? extends T> action) throws LockTimeoutException, FileIntegrityViolationException {
        return readFile((Factory<T>) () -> {
            return (T) action;
        });
    }
}