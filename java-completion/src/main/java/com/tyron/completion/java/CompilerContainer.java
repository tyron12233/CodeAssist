package com.tyron.completion.java;

import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

import kotlin.jvm.functions.Function1;

/**
 * A container class for compiled information, used for thread safety
 */
public class CompilerContainer implements AutoCloseable {

    private volatile CompileTask mCompileTask;

    /**
     * This is for codes that will use the compile information,
     * it ensures that all other threads accessing the compile information
     * are synchronized
     */
    public synchronized void run(Consumer<CompileTask> consumer) {
        consumer.accept(mCompileTask);
    }

    public synchronized <T> T get(Function1<CompileTask, T> fun) {
        return fun.invoke(mCompileTask);
    }

    public synchronized void setCompileTask(CompileTask task) {
        mCompileTask = task;
    }

    @Override
    public void close() {
        if (mCompileTask != null) {
            mCompileTask.close();
        }
    }
}
