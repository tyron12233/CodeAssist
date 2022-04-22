package com.tyron.builder.cache.internal;

import static com.tyron.builder.cache.FileLockManager.LockMode.Exclusive;

import com.google.common.util.concurrent.Runnables;
import com.tyron.builder.api.Action;
import com.tyron.builder.internal.Actions;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.cache.CrossProcessCacheAccess;
import com.tyron.builder.cache.FileLock;
import com.tyron.builder.cache.FileLockManager;
import com.tyron.builder.cache.FileLockReleasedSignal;
import com.tyron.builder.cache.LockOptions;

import java.io.File;
/**
 * A {@link CrossProcessCacheAccess} implementation used when a cache is opened with an exclusive lock that is held until the cache is closed. This implementation is simply a no-op for these methods.
 */
public class FixedExclusiveModeCrossProcessCacheAccess extends AbstractCrossProcessCacheAccess {
    private final static Action<FileLockReleasedSignal> NO_OP_CONTENDED_ACTION = Actions.doNothing();

    private final String cacheDisplayName;
    private final File lockTarget;
    private final LockOptions lockOptions;
    private final FileLockManager lockManager;
    private final CacheInitializationAction initializationAction;
    private final Action<FileLock> onOpenAction;
    private final Action<FileLock> onCloseAction;
    private FileLock fileLock;

    public FixedExclusiveModeCrossProcessCacheAccess(String cacheDisplayName, File lockTarget, LockOptions lockOptions, FileLockManager lockManager, CacheInitializationAction initializationAction, Action<FileLock> onOpenAction, Action<FileLock> onCloseAction) {
        assert lockOptions.getMode() == Exclusive;
        this.initializationAction = initializationAction;
        this.onOpenAction = onOpenAction;
        this.onCloseAction = onCloseAction;
        assert lockOptions.getMode() == Exclusive;
        this.cacheDisplayName = cacheDisplayName;
        this.lockTarget = lockTarget;
        this.lockOptions = lockOptions;
        this.lockManager = lockManager;
    }

    @Override
    public void open() {
        if (fileLock != null) {
            throw new IllegalStateException("File lock " + lockTarget + " is already open.");
        }
        final FileLock fileLock = lockManager.lock(lockTarget, lockOptions, cacheDisplayName, "", NO_OP_CONTENDED_ACTION);
        try {
            boolean rebuild = initializationAction.requiresInitialization(fileLock);
            if (rebuild) {
                fileLock.writeFile(new Runnable() {
                    @Override
                    public void run() {
                        initializationAction.initialize(fileLock);
                    }
                });
            }
            onOpenAction.execute(fileLock);
        } catch (Exception e) {
            fileLock.close();
            throw UncheckedException.throwAsUncheckedException(e);
        }
        this.fileLock = fileLock;
    }

    @Override
    public void close() {
        if (fileLock != null) {
            try {
                onCloseAction.execute(fileLock);
                fileLock.close();
            } finally {
                fileLock = null;
            }
        }
    }

    @Override
    public Runnable acquireFileLock() {
        return Runnables.doNothing();
    }

    @Override
    public <T> T withFileLock(Factory<T> factory) {
        return factory.create();
    }

}