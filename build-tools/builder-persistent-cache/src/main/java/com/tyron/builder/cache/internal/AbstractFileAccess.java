package com.tyron.builder.cache.internal;

import com.tyron.builder.internal.Factory;
import com.tyron.builder.cache.FileAccess;
import com.tyron.builder.cache.FileIntegrityViolationException;
import com.tyron.builder.cache.LockTimeoutException;

import java.util.concurrent.Callable;

public abstract class AbstractFileAccess implements FileAccess {
    @Override
    public <T> T readFile(final Callable<? extends T> action) throws LockTimeoutException, FileIntegrityViolationException {
        return readFile(new Factory<T>() {
            @Override
            public T create() {
                //noinspection unchecked
                return (T) action;
            }
        });
    }
}