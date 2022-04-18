package com.tyron.builder.internal.resources;

public interface ResourceLockContainer {
    void lockAcquired(ResourceLock lock);

    void lockReleased(ResourceLock lock);
}