package com.tyron.builder.cache;

import java.io.File;

/**
 * Thrown on timeout acquiring a lock on a file.
 */
public class LockTimeoutException extends RuntimeException {
    private final File lockFile;

    public LockTimeoutException(String message, File lockFile) {
        super(message);
        this.lockFile = lockFile;
    }

    public File getLockFile() {
        return lockFile;
    }
}