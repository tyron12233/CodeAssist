package org.gradle.internal.execution.steps;

import org.gradle.internal.Try;
import org.gradle.internal.execution.DeferredExecutionHandler;
import org.gradle.internal.execution.UnitOfWork;
import org.gradle.internal.execution.UnitOfWork.Identity;
import org.gradle.cache.Cache;

public interface DeferredExecutionAwareStep<C extends Context, R extends Result> extends Step<C, R> {
    <T, O> T executeDeferred(UnitOfWork work, C context, Cache<Identity, Try<O>> cache, DeferredExecutionHandler<O, T> handler);
}