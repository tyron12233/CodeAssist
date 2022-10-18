package org.gradle.internal.execution;

import org.gradle.internal.Try;

import java.util.function.Supplier;

public interface DeferredExecutionHandler<O, T> {
    T processCachedOutput(Try<O> cachedResult);

    T processDeferredOutput(Supplier<Try<O>> deferredExecution);
}