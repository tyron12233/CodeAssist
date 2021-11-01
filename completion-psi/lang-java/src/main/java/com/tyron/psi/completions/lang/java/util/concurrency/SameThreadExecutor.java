package com.tyron.psi.completions.lang.java.util.concurrency;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executor;

public final class SameThreadExecutor implements Executor {
    private SameThreadExecutor() { }

    public static final Executor INSTANCE = new SameThreadExecutor();

    @Override
    public void execute(@NotNull Runnable command) {
        command.run();
    }
}
