package com.tyron.psi.completions.lang.java.util;

import com.tyron.psi.completions.lang.java.util.concurrency.AsyncFuture;
import com.tyron.psi.completions.lang.java.util.concurrency.AsyncFutureFactory;
import com.tyron.psi.completions.lang.java.util.concurrency.AsyncFutureResult;
import com.tyron.psi.completions.lang.java.util.concurrency.AsyncUtil;
import com.tyron.psi.completions.lang.java.util.concurrency.DefaultResultConsumer;
import com.tyron.psi.completions.lang.java.util.concurrency.SameThreadExecutor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.util.AbstractQuery;
import org.jetbrains.kotlin.com.intellij.util.Processor;
import org.jetbrains.kotlin.com.intellij.util.Query;

import java.util.concurrent.ExecutionException;

public class MergeQuery<T> extends AbstractQuery<T> {
    private final Query<? extends T> myQuery1;
    private final Query<? extends T> myQuery2;

    public MergeQuery(@NotNull Query<? extends T> query1, @NotNull Query<? extends T> query2) {
        myQuery1 = query1;
        myQuery2 = query2;
    }

    @Override
    protected boolean processResults(@NotNull Processor<? super T> consumer) {
        return delegateProcessResults(myQuery1, consumer) && delegateProcessResults(myQuery2, consumer);
    }

    @Override
    public boolean forEach(@NotNull Processor<? super T> consumer) {
        try {
            return forEachAsync(consumer).get();
        } catch (ExecutionException | InterruptedException e) {
            return false;
        }
    }

    @NotNull
    public AsyncFuture<Boolean> forEachAsync(@NotNull final Processor<? super T> consumer) {
        final AsyncFutureResult<Boolean> result = AsyncFutureFactory.getInstance().createAsyncFutureResult();

        AsyncFuture<Boolean> fq = AsyncUtil.wrapBoolean(myQuery1.forEach(consumer));

        fq.addConsumer(SameThreadExecutor.INSTANCE, new DefaultResultConsumer<Boolean>(result) {
            @Override
            public void onSuccess(Boolean value) {
                if (value) {
                    AsyncFuture<Boolean> fq2 = AsyncUtil.wrapBoolean(myQuery2.forEach(consumer));
                    fq2.addConsumer(SameThreadExecutor.INSTANCE, new DefaultResultConsumer<>(result));
                }
                else {
                    result.set(false);
                }
            }
        });
        return result;
    }

}
