package com.tyron.builder.api.internal.execution;

import com.tyron.builder.api.internal.Try;

import java.util.function.Supplier;

public interface DeferredExecutionHandler<O, T> {
    T processCachedOutput(Try<O> cachedResult);

    T processDeferredOutput(Supplier<Try<O>> deferredExecution);
}