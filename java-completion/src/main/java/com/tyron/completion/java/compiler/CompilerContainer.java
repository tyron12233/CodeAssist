package com.tyron.completion.java.compiler;

import android.util.Log;

import androidx.annotation.GuardedBy;

import com.tyron.completion.java.BuildConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import kotlin.jvm.functions.Function1;

/**
 * A container class for compiled information, used for thread safety
 *
 * A read is when the {@link CompileTask} is being accessed to get information about the parse tree.
 * A write is when the {@link CompileTask} is being changed from a compile call
 *
 * Any threads are allowed to read only if there is no thread that is currently writing.
 * If there is a thread that is currently writing, all other threads that attempts to read
 * will be blocked until the write thread has finished.
 *
 * Only one thread is allowed to write at a time, during a write operation all threads that
 * attempts to read will be blocked until the thread writing has finished.
 */
public class CompilerContainer {

    private static final String TAG = CompilerContainer.class.getSimpleName();

    private boolean mIsWriting;

    @GuardedBy("mLock")
    private CompileTask mCompileTask;

    private final ReentrantReadWriteLock mLock = new ReentrantReadWriteLock(true);
    private final Lock mReadLock = mLock.readLock();
    private final Lock mWriteLock = mLock.writeLock();

    public CompilerContainer() {

    }

    /**
     * This is for codes that will use the compile information,
     * it ensures that all other threads accessing the compile information
     * are synchronized
     */
    public void run(Consumer<CompileTask> consumer) {
        mReadLock.lock();
        try {
            consumer.accept(mCompileTask);
        } finally {
            mReadLock.unlock();
        }
    }

    public <T> T get(Function<CompileTask, T> fun) {
        mReadLock.lock();
        try {
            return fun.apply(mCompileTask);
        } finally {
            mReadLock.unlock();
        }
    }

    public boolean isWriting() {
        return mIsWriting;
    }

    void initialize(Supplier<CompileTask> supplier) {
        mWriteLock.lock();
        mIsWriting = true;

        if (mCompileTask != null) {
            mCompileTask.close();
        }

        try {
            mCompileTask = supplier.get();
        } finally {
            mIsWriting = false;
            mWriteLock.unlock();
        }
    }
}
