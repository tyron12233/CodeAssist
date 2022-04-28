package com.tyron.builder.api.internal.changedetection.state;

import static com.tyron.builder.cache.internal.filelock.LockOptionsBuilder.mode;

import com.tyron.builder.internal.execution.history.ExecutionHistoryCacheAccess;
import com.tyron.builder.cache.FileLockManager;
import com.tyron.builder.cache.PersistentCache;
import com.tyron.builder.cache.internal.filelock.LockOptionsBuilder;
import com.tyron.builder.cache.scopes.ScopedCache;

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