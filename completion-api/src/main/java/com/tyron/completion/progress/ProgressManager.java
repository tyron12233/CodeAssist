package com.tyron.completion.progress;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private final ExecutorService mPool = Executors.newFixedThreadPool(8);
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private final Map<Thread, ProgressIndicator> mThreadToIndicator;

    public ProgressManager() {
        mThreadToIndicator = new HashMap<>();
    }

    /**
     * Run a cancelable asynchronous task.
     * @param runnable The task to run
     * @param cancelConsumer The code to run when this task has been canceled,
     *                      called from background thread
     * @param indicator The class used to control this task's execution
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
     * Run an asynchronous operation that is not cancelable.
     * @param runnable The code to run
     */
    public void runNonCancelableAsync(Runnable runnable) {
        mPool.execute(runnable);
    }

    /**
     * Posts the runnable into the UI thread to be run later.
     * @param runnable The code to run
     */
    public void runLater(Runnable runnable) {
        mMainHandler.post(runnable);
    }

    private void doCheckCanceled() {
        ProgressIndicator indicator = mThreadToIndicator.get(Thread.currentThread());
        if (indicator != null) {
            if (indicator.isCanceled()) {
                throw new ProcessCanceledException();
            }
        }
    }
}
