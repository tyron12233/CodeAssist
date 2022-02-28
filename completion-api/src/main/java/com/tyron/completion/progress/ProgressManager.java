package com.tyron.completion.progress;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.common.util.concurrent.AsyncCallable;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public class ProgressManager {

    private static ProgressManager sInstance = null;

    public static ProgressManager getInstance() {
        if (sInstance == null) {
            sInstance = new ProgressManager();
        }
        return sInstance;
    }

    public static void checkCanceled() {
        getInstance().doCheckCanceled();
    }

    private final ExecutorService mPool = Executors.newFixedThreadPool(32);
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private final Map<Thread, ProgressIndicator> mThreadToIndicator;

    public ProgressManager() {
        mThreadToIndicator = new HashMap<>();
    }

    /**
     * Run a cancelable asynchronous task.
     *
     * @param runnable       The task to run
     * @param cancelConsumer The code to run when this task has been canceled,
     *                       called from background thread
     * @param indicator      The class used to control this task's execution
     */
    public void runAsync(Runnable runnable,
                         Consumer<ProgressIndicator> cancelConsumer,
                         ProgressIndicator indicator) {
        mPool.execute(() -> {
            Thread currentThread = Thread.currentThread();
            try {
                mThreadToIndicator.put(currentThread, indicator);
                indicator.setRunning(true);
                runnable.run();
            } catch (ProcessCanceledException e) {
                cancelConsumer.accept(indicator);
            } finally {
                indicator.setRunning(false);
                mThreadToIndicator.remove(currentThread);
            }
        });
    }

    /**
     * Run a non cancelable task in the background. If the task has been running for more than
     * two seconds,
     * The loadingRunnable will be run. If the task has finished before 2000, the loadingRunnable
     * will not be called.
     *
     * @param taskToRun       The task to run
     * @param loadingRunnable The runnable to run if the task has been running for more than 2
     *                        seconds
     * @param finishRunnable  The task to run after the task has finished
     */
    public void runNonCancelableAsync(Runnable taskToRun,
                                      Runnable loadingRunnable,
                                      Runnable finishRunnable) {
        runNonCancelableAsync(() -> {
            taskToRun.run();
            cancelRunLater(loadingRunnable);
            finishRunnable.run();
        });
        runLater(loadingRunnable, 2000);
    }

    /**
     * Run an asynchronous operation that is not cancelable.
     *
     * @param runnable The code to run
     */
    public void runNonCancelableAsync(Runnable runnable) {
        mPool.execute(runnable);
    }

    public <T> ListenableFuture<T> computeNonCancelableAsync(AsyncCallable<T> callable) {
        return Futures.submitAsync(callable, mPool);
    }

    /**
     * Posts the runnable into the UI thread to be run later.
     *
     * @param runnable The code to run
     */
    public void runLater(Runnable runnable) {
        mMainHandler.post(runnable);
    }

    /**
     * Posts the runnable into the UI thread to be run later.
     *
     * @param runnable The code to run
     */
    public void runLater(Runnable runnable, long delay) {
        mMainHandler.postDelayed(runnable, delay);
    }

    public void cancelRunLater(Runnable runnable) {
        mMainHandler.removeCallbacks(runnable);
    }

    public void cancelThread(Thread thread) {
        ProgressIndicator indicator = mThreadToIndicator.get(thread);
        if (indicator == null) {
            indicator = new ProgressIndicator();
        }
        indicator.cancel();
        mThreadToIndicator.put(thread, indicator);
    }

    private void doCheckCanceled() {
        ProgressIndicator indicator = mThreadToIndicator.get(Thread.currentThread());
        if (indicator != null) {
            if (indicator.isCanceled()) {
                mThreadToIndicator.remove(Thread.currentThread());
                throw new ProcessCanceledException();
            }
        }
    }
}
