package com.tyron.completion.progress;

public class ProgressIndicator {

    private volatile boolean mCanceled;
    private volatile boolean mRunning;

    public ProgressIndicator() {

    }

    public void setCanceled(boolean cancel) {
        mCanceled = cancel;
    }

    public void cancel() {
        setCanceled(true);
    }

    public boolean isCanceled() {
        return mCanceled;
    }

    public void setRunning(boolean b) {
        mRunning = b;
    }

    public boolean isRunning() {
        return mRunning;
    }
}
