package org.jetbrains.kotlin.com.intellij.openapi.progress.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.progress.Cancellation;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.progress.impl.CoreProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.util.ThrowableComputable;
import org.jetbrains.kotlin.com.intellij.util.ConcurrencyUtil;
import org.jetbrains.kotlin.com.intellij.util.ExceptionUtil;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;

import jdk.internal.org.jline.utils.Log;

public class ProgressIndicatorUtils {


    private static final Logger LOG = Logger.getInstance(ProgressIndicatorUtils.class);

    public static <T> T awaitWithCheckCanceled(@NotNull Future<T> future) {
        ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
        return awaitWithCheckCanceled(future, indicator);
    }

    public static void awaitWithCheckCanceled(@NotNull Condition condition) {
        awaitWithCheckCanceled(() -> condition.await(ConcurrencyUtil.DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    public static void awaitWithCheckCanceled(@NotNull ThrowableComputable<Boolean, ? extends Exception> waiter) {
        ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
        boolean success = false;
        while (!success) {
            checkCancelledEvenWithPCEDisabled(indicator);
            try {
                success = waiter.compute();
            }
            catch (ProcessCanceledException pce) {
                throw pce;
            }
            catch (Exception e) {
                //noinspection InstanceofCatchParameter
                if (!(e instanceof InterruptedException)) {
                    LOG.warn(e);
                }
                throw new ProcessCanceledException(e);
            }
        }
    }


    public static <T> T awaitWithCheckCanceled(@NotNull Future<T> future, @Nullable ProgressIndicator indicator) {
        while (true) {
            checkCancelledEvenWithPCEDisabled(indicator);
            try {
                return future.get(ConcurrencyUtil.DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
            catch (TimeoutException | RejectedExecutionException ignore) {
            }
            catch (InterruptedException e) {
                throw new ProcessCanceledException(e);
            }
            catch (Throwable e) {
                Throwable cause = e.getCause();
                if (cause instanceof ProcessCanceledException) {
                    throw (ProcessCanceledException)cause;
                }
                if (cause instanceof CancellationException) {
                    throw new ProcessCanceledException(cause);
                }
                ExceptionUtil.rethrow(e);
            }
        }
    }

    /** Use when a deadlock is possible otherwise. */
    public static void checkCancelledEvenWithPCEDisabled(@Nullable ProgressIndicator indicator) {
        if (Cancellation.isInNonCancelableSection()) {
            // just run the hooks, don't check for cancellation in non-cancellable section
            ((CoreProgressManager) ProgressManager.getInstance()).runCheckCanceledHooks(indicator);
            return;
        }
//        Cancellation.checkCancelled();
        if (indicator == null) return;
        indicator.checkCanceled();              // check for cancellation as usual and run the hooks
        if (indicator.isCanceled()) {           // if a just-canceled indicator or PCE is disabled
            indicator.checkCanceled();            // ... let the just-canceled indicator provide a customized PCE
            throw new ProcessCanceledException(); // ... otherwise PCE is disabled so throw it manually
        }
    }
}
