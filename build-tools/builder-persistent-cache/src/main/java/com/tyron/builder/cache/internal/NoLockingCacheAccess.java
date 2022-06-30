package com.tyron.builder.cache.internal;

import com.tyron.builder.internal.Factory;

class NoLockingCacheAccess extends AbstractCrossProcessCacheAccess {

    private final Runnable onClose;

    NoLockingCacheAccess(Runnable onClose) {
        this.onClose = onClose;
    }

    @Override
    public void open() {
        // Don't need to do anything
    }

    @Override
    public void close() {
        onClose.run();
    }

    @Override
    public <T> T withFileLock(Factory<T> factory) {
        return factory.create();
    }

    @Override
    public Runnable acquireFileLock() {
        return () -> {};
    }
}