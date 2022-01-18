package com.tyron.completion.java;

import androidx.annotation.GuardedBy;

import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

import kotlin.jvm.functions.Function1;

/**
 * A container class for compiled information, used for thread safety
 */
public class CompilerContainer {

    @GuardedBy("mLock")
    private volatile CompileTask mCompileTask;

    private final Object mLock = new Object();

    /**
     * This is for codes that will use the compile information,
     * it ensures that all other threads accessing the compile information
     * are synchronized
     */
    public void run(Consumer<CompileTask> consumer) {
        synchronized (mLock) {
            try {
                consumer.accept(mCompileTask);
            } finally {
                mCompileTask.close();
            }
        }
    }

    public <T> T get(Function1<CompileTask, T> fun) {
        synchronized (mLock) {
            try {
                return fun.invoke(mCompileTask);
            } finally {
                mCompileTask.close();
            }
        }
    }

    void initialize(Runnable runnable) {
        synchronized (mLock) {
            runnable.run();
        }
    }


    void setCompileTask(CompileTask task) {
        mCompileTask = task;
    }
}
