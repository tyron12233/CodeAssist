package com.tyron.builder.cache.internal.filelock;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

public class LockFileAccess {

    private final RandomAccessFile lockFileAccess;

    private final LockStateAccess lockStateAccess;
    private final LockInfoAccess lockInfoAccess;

    public LockFileAccess(File lockFile, LockStateAccess lockStateAccess) throws FileNotFoundException {
        this.lockFileAccess = new RandomAccessFile(lockFile, "rw");
        this.lockStateAccess = lockStateAccess;
        lockInfoAccess = new LockInfoAccess(this.lockStateAccess.getRegionEnd());
    }

    public void close() throws IOException {
        lockFileAccess.close();
    }

    public void writeLockInfo(int port, long lockId, String pid, String operation) throws IOException {
        LockInfo lockInfo = new LockInfo();
        lockInfo.port = port;
        lockInfo.lockId = lockId;
        lockInfo.pid = pid;
        lockInfo.operation = operation;
        lockInfoAccess.writeLockInfo(lockFileAccess, lockInfo);
    }

    public LockInfo readLockInfo() throws IOException {
        return lockInfoAccess.readLockInfo(lockFileAccess);
    }

    /**
     * Reads the lock state from the lock file, possibly writing out a new lock file if not present or empty.
     */
    public LockState ensureLockState() throws IOException {
        return lockStateAccess.ensureLockState(lockFileAccess);
    }

    public LockState markClean(LockState lockState) throws IOException {
        LockState newState = lockState.completeUpdate();
        lockStateAccess.writeState(lockFileAccess, newState);
        return newState;
    }

    public LockState markDirty(LockState lockState) throws IOException {
        LockState newState = lockState.beforeUpdate();
        lockStateAccess.writeState(lockFileAccess, newState);
        return newState;
    }

    public void clearLockInfo() throws IOException {
        lockInfoAccess.clearLockInfo(lockFileAccess);
    }

    public FileLockOutcome tryLockInfo(boolean shared) throws IOException {
        return lockInfoAccess.tryLock(lockFileAccess, shared);
    }

    public FileLockOutcome tryLockState(boolean shared) throws IOException {
        return lockStateAccess.tryLock(lockFileAccess, shared);
    }

    /**
     * Reads the lock state from the lock file.
     */
    public LockState readLockState() throws IOException {
        return lockStateAccess.readState(lockFileAccess);
    }
}