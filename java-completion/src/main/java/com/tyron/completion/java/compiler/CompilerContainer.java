package com.tyron.completion.java.compiler;

import android.util.Log;

import androidx.annotation.GuardedBy;

import com.tyron.completion.java.BuildConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    @GuardedBy("mLock")
    private volatile CompileTask mCompileTask;

    private final Object mLock = new Object();
    private final List<Thread> mReaders = Collections.synchronizedList(new ArrayList<>());


    public CompilerContainer() {

    }

    private void closeIfEmpty() {
        if (mReaders.isEmpty()) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, Thread.currentThread().getName() + " has closed the compile task.");
            }
            if (mCompileTask != null) {
                mCompileTask.close();
            }
        }
    }

    /**
     * This is for codes that will use the compile information,
     * it ensures that all other threads accessing the compile information
     * are synchronized
     */
    public void run(Consumer<CompileTask> consumer) {
        waitForWriter();
        mReaders.add(Thread.currentThread());
        try {
            consumer.accept(mCompileTask);
        } finally {
            mReaders.remove(Thread.currentThread());
        }
    }

    public <T> T get(Function1<CompileTask, T> fun) {
        waitForWriter();
        mReaders.add(Thread.currentThread());
        try {
            return fun.invoke(mCompileTask);
        } finally {
            mReaders.remove(Thread.currentThread());
        }
    }

    public <T> T getWithLock(Function1<CompileTask, T> fun) {
        waitForWriter();
        waitForReaders();
        try {
            mReaders.add(Thread.currentThread());
            return fun.invoke(mCompileTask);
        } finally {
            mReaders.remove(Thread.currentThread());
        }
    }

    public boolean isWriting() {
        return mIsWriting;
    }

    void initialize(Runnable runnable) {
        synchronized (mLock) {
            assertIsNotReader();
            waitForReaders();
            try {
                mIsWriting = true;
                runnable.run();
            } finally {
                mIsWriting = false;
            }
        }
    }

    private void waitForReaders() {
        if (!mReaders.isEmpty()) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, Thread.currentThread().getName() + " is waiting for readers:" +
                           mReaders);
            }
        }
        while (true) {
            if (mReaders.isEmpty()) {
                closeIfEmpty();
                return;
            }
        }
    }

    private void waitForWriter() {
        if (mIsWriting) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, Thread.currentThread().getName() + " is waiting for writer");
            }
        }
        while (true) {
            if (!mIsWriting) {
                return;
            }
        }
    }

    private void assertIsNotReader() {
        if (mReaders.contains(Thread.currentThread())) {
            throw new RuntimeException("Cannot compile inside a container.");
        }
    }


    void setCompileTask(CompileTask task) {
        mCompileTask = task;
    }
}
