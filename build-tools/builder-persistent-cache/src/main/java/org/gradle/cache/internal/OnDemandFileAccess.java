package org.gradle.cache.internal;

import org.gradle.cache.FileIntegrityViolationException;
import org.gradle.cache.FileLock;
import org.gradle.cache.FileLockManager;
import org.gradle.cache.LockTimeoutException;
import org.gradle.internal.Factory;

import java.io.File;

import static org.gradle.cache.internal.filelock.LockOptionsBuilder.mode;

public class OnDemandFileAccess extends AbstractFileAccess {
    private final String displayName;
    private final FileLockManager manager;
    private final File targetFile;

    public OnDemandFileAccess(File targetFile, String displayName, FileLockManager manager) {
        this.targetFile = targetFile;
        this.displayName = displayName;
        this.manager = manager;
    }

    @Override
    public <T> T readFile(Factory<? extends T> action) throws LockTimeoutException, FileIntegrityViolationException {
        FileLock lock = manager.lock(targetFile, mode(FileLockManager.LockMode.Shared), displayName);
        try {
            return lock.readFile(action);
        } finally {
            lock.close();
        }
    }

    @Override
    public void updateFile(Runnable action) throws LockTimeoutException, FileIntegrityViolationException {
        FileLock lock = manager.lock(targetFile, mode(FileLockManager.LockMode.Exclusive), displayName);
        try {
            lock.updateFile(action);
        } finally {
            lock.close();
        }
    }

    @Override
    public void writeFile(Runnable action) throws LockTimeoutException {
        FileLock lock = manager.lock(targetFile, mode(FileLockManager.LockMode.Exclusive), displayName);
        try {
            lock.writeFile(action);
        } finally {
            lock.close();
        }
    }
}
