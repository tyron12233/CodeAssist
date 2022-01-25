package com.tyron.completion.progress;

import android.util.Log;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ProgressManager {

    private static ProgressManager sInstance = null;

    private volatile boolean mIsCanceled;
    private volatile boolean mIsRunning;

    public static ProgressManager getInstance() {
        if (sInstance == null) {
            sInstance = new ProgressManager();
        }
        return sInstance;
    }

    private final Map<Thread, Boolean> mThreads = Collections.synchronizedMap(new HashMap<>());

    public void start() {
        start(Thread.currentThread());
    }

    public void start(Thread thread) {
        mThreads.put(thread, false);
    }

    public void end() {
        mThreads.remove(Thread.currentThread());
    }

    public synchronized void setRunning(boolean running) {
        mIsRunning = running;
    }

    public synchronized void setCanceled(boolean cancel) {
        mThreads.remove(Thread.currentThread());
        mThreads.put(Thread.currentThread(), cancel);
    }

    public synchronized boolean isRunning() {
        return mIsRunning;
    }

    public synchronized boolean isCanceled() {
        return mIsCanceled;
    }

    public static void checkCanceled() throws ProcessCanceledException {
        getInstance().doCheckCancelled();
    }

    public synchronized void doCheckCancelled() throws ProcessCanceledException {
        Boolean b = mThreads.get(Thread.currentThread());
        if (b != null) {
            if (b) {
                throw new ProcessCanceledException();
            }
        }
    }

    public void execute(Runnable runnable) {
        Thread currentThread = Thread.currentThread();
        if (mThreads.containsKey(currentThread)) {
            while (isRunning(currentThread)) {
                cancel(currentThread);
            }
        }
        start();
        try {
            runnable.run();
        } catch (ProcessCanceledException e) {
            Log.d("ProgressManager", currentThread + " canceled", e);
        } finally {
            end();
        }
    }

    private boolean isRunning(Thread thread) {
        Boolean aBoolean = mThreads.get(thread);
        if (aBoolean != null) {
            return !aBoolean;
        }
        return false;
    }


    public void cancel(Thread thread) {
        if (!isRunning(thread)) {
            return;
        }

        mThreads.remove(thread);
        mThreads.put(thread, true);
    }
}
