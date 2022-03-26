package com.tyron.completion.java.compiler;

import android.util.Log;

import androidx.annotation.GuardedBy;

import com.tyron.completion.java.BuildConfig;
import com.tyron.completion.progress.ProcessCanceledException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

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

    private volatile boolean mIsWriting;

    private final Semaphore semaphore = new Semaphore(1);

    private CompileTask mCompileTask;

    public CompilerContainer() {

    }

    /**
     * This is for codes that will use the compile information,
     * it ensures that all other threads accessing the compile information
     * are synchronized
     */
    public void run(Consumer<CompileTask> consumer) {
        semaphore.acquireUninterruptibly();
        try {
            consumer.accept(mCompileTask);
        } finally {
            semaphore.release();
        }
    }

    public <T> T get(Function1<CompileTask, T> fun) {
        semaphore.acquireUninterruptibly();
        try {
            return fun.invoke(mCompileTask);
        } finally {
            semaphore.release();
        }
    }

    public <T> T getWithLock(Function1<CompileTask, T> fun) {
        semaphore.acquireUninterruptibly();
        try {
            return fun.invoke(mCompileTask);
        } finally {
            semaphore.release();
        }

    }

    public synchronized boolean isWriting() {
        return mIsWriting || semaphore.hasQueuedThreads();
    }

    void initialize(Runnable runnable) {
        semaphore.acquireUninterruptibly();
        mIsWriting = true;
        try {
            // ensure that compile task is closed
            if (mCompileTask != null) {
                mCompileTask.close();
            }
            runnable.run();
        } finally {
            mIsWriting = false;
            semaphore.release();
        }
    }

    void setCompileTask(CompileTask task) {
        mCompileTask = task;
    }
}
