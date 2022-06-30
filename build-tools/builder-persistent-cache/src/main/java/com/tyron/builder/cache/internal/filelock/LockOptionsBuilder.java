package com.tyron.builder.cache.internal.filelock;

import com.tyron.builder.cache.FileLockManager;
import com.tyron.builder.cache.LockOptions;

public class LockOptionsBuilder implements LockOptions {

    private FileLockManager.LockMode mode;
    private boolean crossVersion;

    private LockOptionsBuilder(FileLockManager.LockMode mode, boolean crossVersion) {
        this.mode = mode;
        this.crossVersion = crossVersion;
    }

    public static LockOptionsBuilder mode(FileLockManager.LockMode lockMode) {
        return new LockOptionsBuilder(lockMode, false);
    }

    public LockOptionsBuilder useCrossVersionImplementation() {
        crossVersion = true;
        return this;
    }

    @Override
    public FileLockManager.LockMode getMode() {
        return mode;
    }

    @Override
    public boolean isUseCrossVersionImplementation() {
        return crossVersion;
    }

    @Override
    public LockOptions withMode(FileLockManager.LockMode mode) {
        return new LockOptionsBuilder(mode, crossVersion);
    }

    @Override
    public String toString() {
        return mode + " (simple=" + crossVersion + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LockOptionsBuilder)) {
            return false;
        }

        LockOptionsBuilder that = (LockOptionsBuilder) o;

        if (crossVersion != that.crossVersion) {
            return false;
        }
        if (mode != that.mode) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = mode.hashCode();
        result = 31 * result + (crossVersion ? 1 : 0);
        return result;
    }
}