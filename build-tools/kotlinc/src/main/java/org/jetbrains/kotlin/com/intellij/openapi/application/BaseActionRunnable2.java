package org.jetbrains.kotlin.com.intellij.openapi.application;

public abstract class BaseActionRunnable2<T> {

    private boolean mySilentExecution;

    public boolean isSilentExecution() {
        return mySilentExecution;
    }

    protected abstract void run(Result<T> result) throws Throwable;

    public abstract RunResult<T> execute();

    protected boolean canWriteNow() {
        return getApplication().isWriteAccessAllowed();
    }

    protected boolean canReadNow() {
        return getApplication().isReadAccessAllowed();
    }

    protected Application getApplication() {
        return ApplicationManager.getApplication();
    }

    /** Same as execute() but do not log error if exception occurred. */
    public final RunResult<T> executeSilently() {
        mySilentExecution = true;
        return execute();
    }

}