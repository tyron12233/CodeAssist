package com.tyron.builder.internal.execution.steps;

import static com.tyron.builder.internal.execution.UnitOfWork.*;

import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.Try;
import com.tyron.builder.internal.execution.DeferredExecutionHandler;
import com.tyron.builder.internal.execution.ExecutionResult;
import com.tyron.builder.internal.execution.UnitOfWork;
import com.tyron.builder.cache.Cache;

public class IdentityCacheStep<C extends IdentityContext, R extends Result> implements DeferredExecutionAwareStep<C, R> {

    private final Step<? super IdentityContext, ? extends R> delegate;

    public IdentityCacheStep(Step<? super IdentityContext, ? extends R> delegate) {
        this.delegate = delegate;
    }

    @Override
    public R execute(UnitOfWork work, C context) {
        return delegate.execute(work, context);
    }

    @Override
    public <T, O> T executeDeferred(UnitOfWork work, C context, Cache<Identity, Try<O>> cache, DeferredExecutionHandler<O, T> handler) {
        Identity identity = context.getIdentity();
        Try<O> cachedOutput = cache.getIfPresent(identity);
        if (cachedOutput != null) {
            return handler.processCachedOutput(cachedOutput);
        } else {
            return handler.processDeferredOutput(() -> cache.get(
                    identity,
                    () -> execute(work, context).getExecutionResult()
                            .map(ExecutionResult::getOutput)
                            .map(Cast::<O>uncheckedNonnullCast)));
        }
    }
}
