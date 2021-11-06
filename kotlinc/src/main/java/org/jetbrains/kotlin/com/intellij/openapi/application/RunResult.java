package org.jetbrains.kotlin.com.intellij.openapi.application;


import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException;

public class RunResult<T> extends Result<T> {

    private BaseActionRunnable2<T> myActionRunnable;

    private Throwable myThrowable;

    protected RunResult() {
    }

    public RunResult(BaseActionRunnable2<T> action) {
        myActionRunnable = action;
    }

    public RunResult<T> run() {
        try {
            myActionRunnable.run(this);
        }
        catch (ProcessCanceledException e) {
            throw e; // this exception may occur from time to time and it shouldn't be catched
        }
        catch (Throwable throwable) {
            myThrowable = throwable;
            if (!myActionRunnable.isSilentExecution()) {
                if (throwable instanceof RuntimeException) throw (RuntimeException)throwable;
                if (throwable instanceof Error) {
                    throw (Error)throwable;
                }
                else {
                    throw new RuntimeException(myThrowable);
                }
            }
        }
        finally {
            myActionRunnable = null;
        }

        return this;
    }

    public T getResultObject() {
        return myResult;
    }

    public RunResult logException(Logger logger) {
        if (hasException()) {
            logger.error(myThrowable);
        }

        return this;
    }

    public RunResult<T> throwException() throws RuntimeException, Error {
        if (hasException()) {
            if (myThrowable instanceof RuntimeException) {
                throw (RuntimeException)myThrowable;
            }
            if (myThrowable instanceof Error) {
                throw (Error)myThrowable;
            }

            throw new RuntimeException(myThrowable);
        }
        return this;
    }

    public boolean hasException() {
        return myThrowable != null;
    }

    public Throwable getThrowable() {
        return myThrowable;
    }

    public void setThrowable(Exception throwable) {
        myThrowable = throwable;
    }
}
