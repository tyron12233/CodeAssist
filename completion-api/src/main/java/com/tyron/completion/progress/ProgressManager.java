package com.tyron.completion.progress;

public class ProgressManager {

    private static ProgressManager sInstance = null;

    private volatile boolean mIsCanceled;

    public static ProgressManager getInstance() {
        if (sInstance == null) {
            sInstance = new ProgressManager();
        }
        return sInstance;
    }

    public synchronized void setCanceled(boolean cancel) {
        mIsCanceled = cancel;
    }

    public synchronized boolean isCanceled() {
        return mIsCanceled;
    }

    public static void checkCanceled() throws ProcessCanceledException {
        getInstance().doCheckCancelled();
    }

    public synchronized void doCheckCancelled() throws ProcessCanceledException {
        if (mIsCanceled) {
            mIsCanceled = false;
            throw new ProcessCanceledException();
        }
    }
}
