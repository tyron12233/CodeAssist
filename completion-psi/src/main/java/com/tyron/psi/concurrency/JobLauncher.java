package com.tyron.psi.concurrency;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.application.Application;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.application.ex.ApplicationEx;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.kotlin.com.intellij.util.Consumer;
import org.jetbrains.kotlin.com.intellij.util.Processor;

import java.util.List;
import java.util.concurrent.Future;

public abstract class JobLauncher {
    public static JobLauncher getInstance() {
        return ApplicationManager.getApplication().getService(JobLauncher.class);
    }

    /**
     * Schedules concurrent execution of {@code thingProcessor} over each element of {@code things} and waits for completion
     * with checkCanceled in each thread delegated to the {@code progress} (or the current global progress if null).
     * Note: When the {@code thingProcessor} throws an exception or returns {@code false}  or the current indicator is canceled,
     * the method is finished with {@code false} as soon as possible,
     * which means some workers might still be in flight to completion. On the other hand, when the method returns {@code true},
     * it's guaranteed that the whole list was processed and all tasks completed.
     *
     * @param things                      data to process concurrently
     * @param progress                    progress indicator
     * @param thingProcessor              to be invoked concurrently on each element from the collection
     * @return false if tasks have been canceled,
     *         or at least one processor returned false,
     *         or threw an exception,
     *         or we were unable to start read action in at least one thread
     * @throws ProcessCanceledException if at least one task has thrown ProcessCanceledException
     */
    public <T> boolean invokeConcurrentlyUnderProgress(@NotNull List<? extends T> things,
                                                       ProgressIndicator progress,
                                                       @NotNull Processor<? super T> thingProcessor) throws ProcessCanceledException {
        ApplicationEx app = (ApplicationEx)ApplicationManager.getApplication();
        return invokeConcurrentlyUnderProgress(things, progress, app.isReadAccessAllowed(), false, thingProcessor);
    }

    /**
     * Schedules concurrent execution of #thingProcessor over each element of #things and waits for completion
     * With checkCanceled in each thread delegated to our current progress
     *
     * @param things                      data to process concurrently
     * @param progress                    progress indicator
     * @param failFastOnAcquireReadAction if true, returns false when failed to acquire read action
     * @param thingProcessor              to be invoked concurrently on each element from the collection
     * @return false if tasks have been canceled,
     *         or at least one processor returned false,
     *         or threw an exception,
     *         or we were unable to start read action in at least one thread
     * @throws ProcessCanceledException if at least one task has thrown ProcessCanceledException
     * @deprecated use {@link #invokeConcurrentlyUnderProgress(List, ProgressIndicator, Processor)} instead
     */
    @Deprecated
    public <T> boolean invokeConcurrentlyUnderProgress(@NotNull List<? extends T> things,
                                                       ProgressIndicator progress,
                                                       boolean failFastOnAcquireReadAction,
                                                       @NotNull Processor<? super T> thingProcessor) throws ProcessCanceledException {
        return invokeConcurrentlyUnderProgress(things, progress, ApplicationManager.getApplication().isReadAccessAllowed(),
                failFastOnAcquireReadAction, thingProcessor);
    }

    public abstract <T> boolean invokeConcurrentlyUnderProgress(@NotNull List<? extends T> things,
                                                                ProgressIndicator progress,
                                                                boolean runInReadAction,
                                                                boolean failFastOnAcquireReadAction,
                                                                @NotNull Processor<? super T> thingProcessor) throws ProcessCanceledException;

    /**
     * NEVER EVER submit runnable which can lock itself for indeterminate amount of time.
     * This will cause deadlock since this thread pool is an easily exhaustible resource.
     * Use {@link Application#executeOnPooledThread(Runnable)} instead
     */
    @NotNull
    public abstract Job<Void> submitToJobThread(@NotNull final Runnable action, @Nullable Consumer<? super Future<?>> onDoneCallback);
}
