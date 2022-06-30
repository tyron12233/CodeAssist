package com.tyron.builder.wrapper;

import java.io.Closeable;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.concurrent.Callable;

public class ExclusiveFileAccessManager {

    public static final String LOCK_FILE_SUFFIX = ".lck";

    private final int timeoutMs;
    private final int pollIntervalMs;

    public ExclusiveFileAccessManager(int timeoutMs, int pollIntervalMs) {
        this.timeoutMs = timeoutMs;
        this.pollIntervalMs = pollIntervalMs;
    }

    public <T> T access(File exclusiveFile, Callable<T> task) throws Exception {
        final File lockFile = new File(exclusiveFile.getParentFile(), exclusiveFile.getName() + LOCK_FILE_SUFFIX);
        File lockFileDirectory = lockFile.getParentFile();
        if (!lockFileDirectory.mkdirs()
            && (!lockFileDirectory.exists() || !lockFileDirectory.isDirectory())) {
            throw new RuntimeException("Could not create parent directory for lock file " + lockFile.getAbsolutePath());
        }
        RandomAccessFile randomAccessFile = null;
        FileChannel channel = null;
        try {

            long expiry = getTimeMillis() + timeoutMs;
            FileLock lock = null;

            while (lock == null && getTimeMillis() < expiry) {
                randomAccessFile = new RandomAccessFile(lockFile, "rw");
                channel = randomAccessFile.getChannel();
                lock = channel.tryLock();

                if (lock == null) {
                    maybeCloseQuietly(channel);
                    maybeCloseQuietly(randomAccessFile);
                    Thread.sleep(pollIntervalMs);
                }
            }

            if (lock == null) {
                throw new RuntimeException("Timeout of " + timeoutMs + " reached waiting for exclusive access to file: " + exclusiveFile.getAbsolutePath());
            }

            try {
                return task.call();
            } finally {
                lock.release();

                maybeCloseQuietly(channel);
                channel = null;
                maybeCloseQuietly(randomAccessFile);
                randomAccessFile = null;
            }
        } finally {
            maybeCloseQuietly(channel);
            maybeCloseQuietly(randomAccessFile);
        }
    }

    private long getTimeMillis() {
        return System.nanoTime() / (1000L * 1000L);
    }

    private static void maybeCloseQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignore) {
                //
            }
        }
    }
}
