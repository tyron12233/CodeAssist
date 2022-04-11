package com.tyron.builder.cache.internal.filelock;


import java.nio.channels.FileLock;

public abstract class FileLockOutcome {
    public static final FileLockOutcome LOCKED_BY_ANOTHER_PROCESS = new FileLockOutcome() {
    };
    public static final FileLockOutcome LOCKED_BY_THIS_PROCESS = new FileLockOutcome() {
    };

    public boolean isLockWasAcquired() {
        return false;
    }

    public FileLock getFileLock() {
        throw new IllegalStateException("Lock was not acquired");
    }

    static FileLockOutcome acquired(FileLock fileLock) {
        return new FileLockOutcome() {
            @Override
            public boolean isLockWasAcquired() {
                return true;
            }

            @Override
            public FileLock getFileLock() {
                return fileLock;
            }
        };
    }
}