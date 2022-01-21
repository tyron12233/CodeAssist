package org.jetbrains.kotlin.com.intellij.util;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicatorProvider;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.progress.impl.CoreProgressManager;
import org.jetbrains.kotlin.com.intellij.util.containers.ConcurrentList;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public final class ReadMostlyRWLock {

    @NonNull
    final Thread writeThread;
    @VisibleForTesting
    volatile boolean writeRequested;
    private final AtomicBoolean writeIntent = new AtomicBoolean(false);
    private volatile boolean writeAcquired;
    private final ConcurrentList<Reader> readers = ContainerUtil.createConcurrentList();

    private volatile boolean writeSuspended;
    private volatile long deadReadersGCStamp;

    public ReadMostlyRWLock(@NonNull Thread writeThread) {
        this.writeThread = writeThread;
    }

    public static class Reader {
        @NonNull
        private final Thread thread;
        volatile boolean readRequested;
        private volatile boolean blocked;
        private volatile boolean impatientReads;

        Reader(@NonNull Thread readerThread) {
            thread = readerThread;
        }

        @Override
        public String toString() {
            return "Reader{" + "thread=" + thread + ", readRequested=" + readRequested + ", " +
                    "blocked=" + blocked + ", impatientReads=" + impatientReads + '}';
        }
    }

    private final ThreadLocal<Reader> R = ThreadLocal.withInitial(() -> {
        Reader status = new Reader(Thread.currentThread());
        boolean added = readers.addIfAbsent(status);
        assert added : readers + "; " + Thread.currentThread();
        return status;
    });

    public boolean isWriteThread() {
        return Thread.currentThread() == writeThread;
    }

    public boolean isReadLockedByThisThread() {
        checkReadThreadAccess();
        Reader status = R.get();
        return status.readRequested;
    }

    public Reader startRead() {
        if (Thread.currentThread() == writeThread) return null;
        Reader status = R.get();
        throwIfImpatient(status);
        if (status.readRequested) return null;

        if (!tryReadLock(status)) {
            ProgressIndicator progress = ProgressIndicatorProvider.getGlobalProgressIndicator();
            for (int iter = 0; ; iter++) {
                if (tryReadLock(status)) {
                    break;
                }

                if (progress != null && progress.isCanceled() && !ProgressManager.getInstance().isInNonCancelableSection()) {
                    throw new ProcessCanceledException();
                }
                waitABit(status, iter);
            }
        }
        return status;
    }

    public Reader startTryRead() {
        if (Thread.currentThread() == writeThread) return null;
        Reader status = R.get();
        throwIfImpatient(status);
        if (status.readRequested) return null;

        tryReadLock(status);
        return status;
    }

    public void endRead(Reader status) {
        checkReadThreadAccess();
        status.readRequested = false;
        if (writeRequested) {
            LockSupport.unpark(writeThread);
        }
    }

    private static final int SPIN_TO_WAIT_FOR_LOCK = 100;

    private void waitABit(Reader status, int iteration) {
        if (iteration > SPIN_TO_WAIT_FOR_LOCK) {
            status.blocked = true;
            try {
                throwIfImpatient(status);
                LockSupport.parkNanos(this, 1_000_000);
            } finally {
                status.blocked = false;
            }
        } else {
            Thread.yield();
        }
    }

    public static class CannotRunReadActionException extends RuntimeException {

    }

    private void throwIfImpatient(Reader status) throws CannotRunReadActionException {
        if (status.impatientReads && writeRequested && !ProgressManager.getInstance().isInNonCancelableSection() && CoreProgressManager.ENABLED) {
            throw new CannotRunReadActionException();
        }
    }

    public boolean isInImpatientReader() {
        return R.get().impatientReads;
    }

    public void executeByImpatientReader(@NonNull Runnable runnable) throws CannotRunReadActionException {
        checkReadThreadAccess();
        Reader status = R.get();
        boolean old = status.impatientReads;
        try {
            status.impatientReads = true;
            runnable.run();
        } finally {
            status.impatientReads = old;
        }
    }

    private boolean tryReadLock(Reader status) {
        throwIfImpatient(status);
        if (!writeRequested) {
            status.readRequested = true;
            if (!writeRequested) {
                return true;
            }
            status.readRequested = false;
        }
        return false;
    }

    public void writeIntentLock() {
        checkWriteThreadAccess();
        for (int iter = 0; ; iter++) {
            if (writeIntent.compareAndSet(false, true)) {
                assert !writeRequested;
                assert !writeAcquired;

                break;
            }

            if (iter > SPIN_TO_WAIT_FOR_LOCK) {
                LockSupport.parkNanos(this, 1_000_000);
            } else {
                Thread.yield();
            }
        }
    }

    public void writeIntentUnlock() {
        checkWriteThreadAccess();

        assert !writeAcquired;
        assert !writeRequested;

        writeIntent.set(false);
        LockSupport.unpark(writeThread);
    }

    public void writeLock() {
        checkWriteThreadAccess();
        assert !writeRequested;
        assert !writeAcquired;

        writeRequested = true;
        for (int iter = 0; ; iter++) {
            if (areAllReadersIdle()) {
                writeAcquired = true;
                break;
            }

            if (iter > SPIN_TO_WAIT_FOR_LOCK) {
                LockSupport.parkNanos(this, 1_000_000);
            } else {
                Thread.yield();
            }
        }
    }

    public void writeSuspendWhilePumpingIdeEventQueueHopingForTheBest(@NonNull Runnable runnable) {
        boolean prev = writeSuspended;
        writeSuspended = true;
        writeUnlock();
        try {
            runnable.run();
        } finally {
            //cancelActionToBeCancelledBeforeWrite();
            writeLock();
            writeSuspended = prev;
        }
    }

    public void writeUnlock() {
        checkWriteThreadAccess();
        writeAcquired = false;
        writeRequested = false;
        List<Reader> dead;
        long current = System.nanoTime();
        if (current - deadReadersGCStamp > 1_000_000) {
            dead = new ArrayList<>(readers.size());
            deadReadersGCStamp = current;
        } else {
            dead = null;
        }
        for (Reader reader : readers) {
            if (reader.blocked) {
                LockSupport.unpark(reader.thread);
            } else if (dead != null && !reader.thread.isAlive()) {
                dead.add(reader);
            }
        }

        if (dead != null) {
            readers.removeAll(dead);
        }
    }

    private void checkWriteThreadAccess() {
        if (Thread.currentThread() != writeThread) {
            throw new IllegalStateException("Current thread: " + Thread.currentThread() + "; " +
                    "expected: " + writeThread);
        }
    }

    private void checkReadThreadAccess() {
        if (Thread.currentThread() == writeThread) {
            throw new IllegalStateException("Must not start read from the write thread: " + Thread.currentThread());
        }
    }

    private boolean areAllReadersIdle() {
        for (Reader reader : readers) {
            if (reader.readRequested) {
                return false;
            }
        }
        return true;
    }

    public boolean isWriteLocked() {
        return writeAcquired;
    }

    @Override
    public String toString() {
        return "ReadMostlyRWLock{" + "writeThread=" + writeThread + ", writeRequested=" + writeRequested + ", writeIntent=" + writeIntent + ", writeAcquired=" + writeAcquired + ", readers=" + readers + ", writeSuspended=" + writeSuspended + ", deadReadersGCStamp=" + deadReadersGCStamp + ", R=" + R + '}';
    }
}
