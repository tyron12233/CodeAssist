package com.tyron.completion.java.compiler;

import android.util.Log;

import androidx.annotation.GuardedBy;

import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.util.Context;
import com.tyron.completion.java.BuildConfig;
import com.tyron.completion.java.compiler.services.CancelService;
import com.tyron.completion.progress.ProcessCanceledException;
import com.tyron.completion.progress.ProgressManager;

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

    private void cancel() {
        if (mCompileTask == null) {
            return;
        }

        JavacTask task = mCompileTask.task;
        if (!(task instanceof JavacTaskImpl)) {
            return;
        }

        JavacTaskImpl taskImpl = ((JavacTaskImpl) task);
        Context context = taskImpl.getContext();
        if (context == null) {
            return;
        }

        ReusableCompiler.CancelServiceImpl cancelService =
                (ReusableCompiler.CancelServiceImpl) ReusableCompiler.CancelServiceImpl.instance(context);
    }

    /**
     * This is for codes that will use the compile information,
     * it ensures that all other threads accessing the compile information
     * are synchronized
     */
    public void run(Consumer<CompileTask> consumer) {
        cancel();
        semaphore.acquireUninterruptibly();
        try {
            consumer.accept(mCompileTask);
        } finally {
            semaphore.release();
        }
    }

    public <T> T get(Function1<CompileTask, T> fun) {
        cancel();
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            throw new ProcessCanceledException();
        }
        try {
            return fun.invoke(mCompileTask);
        } finally {
            semaphore.release();
        }
    }

    public <T> T getWithLock(Function1<CompileTask, T> fun) {
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            throw new ProcessCanceledException();
        }
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
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            throw new ProcessCanceledException();
        }
        mIsWriting = true;
        try {
            // ensure that compile task is closed
            if (mCompileTask != null) {
                mCompileTask.close();
            }

            cancel();

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
