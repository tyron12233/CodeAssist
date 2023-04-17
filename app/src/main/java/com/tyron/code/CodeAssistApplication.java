package com.tyron.code;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.mock.MockApplication;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.application.TransactionGuardImpl;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable;
import org.jetbrains.kotlin.com.intellij.util.ReadMostlyRWLock;
import org.jetbrains.kotlin.com.intellij.util.concurrency.AppExecutorUtil;

import java.util.Stack;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class CodeAssistApplication extends MockApplication {

    private static final Logger LOG = Logger.getInstance(CodeAssistApplication.class);
    private final Thread myWriteThread;

    // defer reading isUnitTest flag until it's initialized
    private static class Holder {
        private static final int ourDumpThreadsOnLongWriteActionWaiting =
                ApplicationManager.getApplication().isUnitTestMode() ? 0 : Integer.getInteger("dump.threads.on.long.write.action.waiting", 0);
    }

    private final ReadMostlyRWLock myLock;
    private final Handler mainHandler = new Handler();
    private boolean myWriteActionPending;

    // contents modified in write action, read in read action
    private final Stack<Class<?>> myWriteActionsStack = new Stack<>();
    private final TransactionGuardImpl myTransactionGuard = new TransactionGuardImpl();
    private int myWriteStackBase;

    public CodeAssistApplication(@NonNull Disposable parentDisposable) {
        this(parentDisposable, Looper.getMainLooper().getThread());
    }

    @VisibleForTesting
    public CodeAssistApplication(@NotNull Disposable parentDisposable, Thread writeThread) {
        super(parentDisposable);

        myLock = new ReadMostlyRWLock(writeThread);
        this.myWriteThread = writeThread;
    }

    @Override
    public boolean isUnitTestMode() {
        return false;
    }

    @Override
    public boolean isDispatchThread() {
        return Thread.currentThread() == myWriteThread;
    }

    @Override
    public boolean isWriteThread() {
        return myLock.isWriteThread();
    }

    @Override
    public void assertIsWriteThread() {
        assert isWriteThread();
    }

    @Override
    public void invokeLater(@NotNull Runnable runnable) {
        mainHandler.post(runnable);
    }

    @Override
    public void runReadAction(@NotNull Runnable action) {
        ReadMostlyRWLock.Reader reader = myLock.startRead();
        try {
          action.run();
        } finally {
            if (reader != null) {
                myLock.endRead(reader);
            }
        }
    }

    @Override
    public <T> T runReadAction(@NotNull Computable<T> computation) {
        ReadMostlyRWLock.Reader reader = myLock.startRead();
        try {
            return computation.get();
        } finally {
            if (reader != null) {
                myLock.endRead(reader);
            }
        }
    }

    private void startWrite(@NotNull Class<?> clazz) {
//        assertWriteIntentLockAcquired();
//        assertNotInsideListener();
        myWriteActionPending = true;
        try {
            fireBeforeWriteActionStart(clazz);
            // otherwise (when myLock is locked) there's a nesting write action:
            // - allow it,
            // - fire listeners for it (somebody can rely on having listeners fired for each write action)
            // - but do not re-acquire any locks because it could be deadlock-level dangerous
            if (!myLock.isWriteLocked()) {
                int delay = Holder.ourDumpThreadsOnLongWriteActionWaiting;
                Future<?> reportSlowWrite = delay <= 0 ? null :
                        AppExecutorUtil.getAppScheduledExecutorService()
                                .scheduleWithFixedDelay(() -> {},
                                        delay, delay, TimeUnit.MILLISECONDS);
                long t = LOG.isDebugEnabled() ? System.currentTimeMillis() : 0;
                myLock.writeLock();
                if (LOG.isDebugEnabled()) {
                    long elapsed = System.currentTimeMillis() - t;
                    if (elapsed != 0) {
                        LOG.debug("Write action wait time: " + elapsed);
                    }
                }
                if (reportSlowWrite != null) {
                    reportSlowWrite.cancel(false);
                }
            }
        } finally {
            myWriteActionPending = false;
        }

        myWriteActionsStack.push(clazz);
    }

    private void endWrite(@NotNull Class<?> clazz) {
        try {
            fireWriteActionFinished(clazz);
            // fire listeners before popping stack because if somebody starts a write-action in a listener,
            // there is a danger of releasing the write-lock before other listeners have been run (since write lock became non-reentrant).
        }
        finally {
            myWriteActionsStack.pop();
            if (myWriteActionsStack.size() == myWriteStackBase) {
                myLock.writeUnlock();
            }
            if (myWriteActionsStack.isEmpty()) {
                fireAfterWriteActionFinished(clazz);
            }
        }
    }

    private void fireAfterWriteActionFinished(Class<?> clazz) {

    }

    private void fireWriteActionFinished(Class<?> clazz) {

    }

    private void fireBeforeWriteActionStart(@NotNull Class<?> action) {
//        myDispatcher.getMulticaster().beforeWriteActionStart(action);
    }

    @Override
    public void runWriteAction(@NotNull Runnable action) {
        Class<? extends Runnable> clazz = action.getClass();
        startWrite(clazz);
        try {
            action.run();
        }
        finally {
            endWrite(clazz);
        }
    }
}
