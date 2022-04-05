package com.tyron.builder.cache.internal.filelock;


import com.tyron.builder.cache.internal.btree.ByteOutput;

import org.apache.commons.io.input.RandomAccessFileInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;

public class LockInfoAccess {
    public static final int INFORMATION_REGION_SIZE = 2052;
    private final LockInfoSerializer lockInfoSerializer = new LockInfoSerializer();
    private final long infoRegionPos;

    public LockInfoAccess(long infoRegionPos) {
        this.infoRegionPos = infoRegionPos;
    }

    public LockInfo readLockInfo(RandomAccessFile lockFileAccess) throws IOException {
        if (lockFileAccess.length() <= infoRegionPos) {
            return new LockInfo();
        } else {
            lockFileAccess.seek(infoRegionPos);

            DataInputStream inputStream = new DataInputStream(new BufferedInputStream(new RandomAccessFileInputStream(lockFileAccess)));
            byte protocolVersion = inputStream.readByte();
            if (protocolVersion != lockInfoSerializer.getVersion()) {
                throw new IllegalStateException(String.format("Unexpected lock protocol found in lock file. Expected %s, found %s.", lockInfoSerializer.getVersion(), protocolVersion));
            }

            return lockInfoSerializer.read(inputStream);
        }
    }

    public void writeLockInfo(RandomAccessFile lockFileAccess, LockInfo lockInfo) throws IOException {
        lockFileAccess.seek(infoRegionPos);

        DataOutputStream outstr = new DataOutputStream(new BufferedOutputStream(new ByteOutput.RandomAccessFileOutputStream(lockFileAccess)));
        outstr.writeByte(lockInfoSerializer.getVersion());
        lockInfoSerializer.write(outstr, lockInfo);
        outstr.flush();

        lockFileAccess.setLength(lockFileAccess.getFilePointer());
    }

    public void clearLockInfo(RandomAccessFile lockFileAccess) throws IOException {
        lockFileAccess.setLength(Math.min(lockFileAccess.length(), infoRegionPos));
    }

    public FileLockOutcome tryLock(RandomAccessFile lockFileAccess, boolean shared) throws IOException {
        try {
            FileLock fileLock = lockFileAccess.getChannel().tryLock(infoRegionPos, INFORMATION_REGION_SIZE - infoRegionPos, shared);
            if (fileLock == null) {
                return FileLockOutcome.LOCKED_BY_ANOTHER_PROCESS;
            } else {
                return FileLockOutcome.acquired(fileLock);
            }
        } catch (OverlappingFileLockException e) {
            // Locked by this process, treat as not acquired
            return FileLockOutcome.LOCKED_BY_THIS_PROCESS;
        }
    }

}