package com.tyron.builder.internal.execution;

import com.tyron.builder.internal.Try;

import java.util.function.Supplier;

public interface DeferredExecutionHandler<O, T> {
    T processCachedOutput(Try<O> cachedResult);

    T processDeferredOutput(Supplier<Try<O>> deferredExecution);
}