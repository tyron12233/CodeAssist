package com.tyron.builder.api.internal.execution;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.Cache;
import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.internal.Try;
import com.tyron.builder.api.internal.execution.history.AfterExecutionState;
import com.tyron.builder.api.internal.origin.OriginMetadata;

import java.util.Optional;
import java.util.function.Supplier;

public interface ExecutionEngine {
    Request createRequest(UnitOfWork work);

    interface Request {
        /**
         * Force the re-execution of the unit of work, disabling optimizations
         * like up-to-date checks, build cache and incremental execution.
         */
        void forceNonIncremental(String nonIncremental);

        /**
         * Set the validation context to use during execution.
         */
        void withValidationContext(WorkValidationContext validationContext);

        /**
         * Execute the unit of work using available optimizations like
         * up-to-date checks, build cache and incremental execution.
         */
        Result execute();

        /**
         * Use an identity cache to store execution results.
         */
        <O> CachedRequest<O> withIdentityCache(Cache<UnitOfWork.Identity, Try<O>> cache);
    }

    interface CachedRequest<O> {
        /**
         * Load the unit of work from the given cache, or defer its execution.
         *
         * If the cache already contains the outputs for the given work, it is passed directly to {@link DeferredExecutionHandler#processCachedOutput(Try)}.
         * Otherwise the execution is wrapped in deferred via {@link DeferredExecutionHandler#processDeferredOutput(Supplier)}.
         * The work is looked up by its {@link UnitOfWork.Identity identity} in the given cache.
         */
        <T> T getOrDeferExecution(DeferredExecutionHandler<O, T> handler);
    }

    interface Result {
        Try<ExecutionResult> getExecutionResult();

//        CachingState getCachingState();

        /**
         * A list of messages describing the first few reasons encountered that caused the work to be executed.
         * An empty list means the work was up-to-date and hasn't been executed.
         */
        ImmutableList<String> getExecutionReasons();

        /**
         * If a previously produced output was reused in some way, the reused output's origin metadata is returned.
         */
        Optional<OriginMetadata> getReusedOutputOriginMetadata();

        /**
         * State after execution.
         */
        @VisibleForTesting
        Optional<AfterExecutionState> getAfterExecutionState();
    }
}