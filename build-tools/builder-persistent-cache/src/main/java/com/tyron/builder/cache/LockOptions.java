package com.tyron.builder.cache;

public interface LockOptions {

    FileLockManager.LockMode getMode();

    boolean isUseCrossVersionImplementation();

    /**
     * Creates a copy of these options with the given mode.
     */
    LockOptions withMode(FileLockManager.LockMode mode);
}