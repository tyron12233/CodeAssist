package com.tyron.builder.api.internal.resources;

public interface ResourceLockContainer {
    void lockAcquired(ResourceLock lock);

    void lockReleased(ResourceLock lock);
}