package org.gradle.api.internal.changedetection.state;

import static org.gradle.cache.internal.filelock.LockOptionsBuilder.mode;

import org.gradle.internal.execution.history.ExecutionHistoryCacheAccess;
import org.gradle.cache.FileLockManager;
import org.gradle.cache.PersistentCache;
import org.gradle.cache.internal.filelock.LockOptionsBuilder;
import org.gradle.cache.scopes.ScopedCache;

import java.io.Closeable;

public class DefaultExecutionHistoryCacheAccess implements ExecutionHistoryCacheAccess, Closeable {
    private final PersistentCache cache;

    public DefaultExecutionHistoryCacheAccess(ScopedCache cacheRepository) {
        this.cache = cacheRepository
                .cache("executionHistory")
                .withDisplayName("execution history cache")
                .withLockOptions(mode(FileLockManager.LockMode.OnDemand)) // Lock on demand
                .open();
    }

    @Override
    public PersistentCache get() {
        return cache;
    }

    @Override
    public void close() {
        cache.close();
    }
}