package com.tyron.builder.internal.execution.steps;

import com.tyron.builder.internal.Try;
import com.tyron.builder.internal.execution.DeferredExecutionHandler;
import com.tyron.builder.internal.execution.UnitOfWork;
import com.tyron.builder.internal.execution.UnitOfWork.Identity;
import com.tyron.builder.cache.Cache;

public interface DeferredExecutionAwareStep<C extends Context, R extends Result> extends Step<C, R> {
    <T, O> T executeDeferred(UnitOfWork work, C context, Cache<Identity, Try<O>> cache, DeferredExecutionHandler<O, T> handler);
}