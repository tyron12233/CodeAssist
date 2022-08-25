package org.gradle.internal.execution.impl;

import org.gradle.api.internal.DocumentationRegistry;
import org.gradle.cache.Cache;
import org.gradle.internal.Try;
import org.gradle.internal.execution.DeferredExecutionHandler;
import org.gradle.internal.execution.ExecutionEngine;
import org.gradle.internal.execution.UnitOfWork;
import org.gradle.internal.execution.UnitOfWork.Identity;
import org.gradle.internal.execution.WorkValidationContext;
import org.gradle.internal.execution.steps.DeferredExecutionAwareStep;
import org.gradle.internal.execution.steps.ExecutionRequestContext;

import java.util.Optional;

public class DefaultExecutionEngine implements ExecutionEngine {
    private final DocumentationRegistry documentationRegistry;
    private final DeferredExecutionAwareStep<? super ExecutionRequestContext, ? extends Result> executeStep;

    public DefaultExecutionEngine(DocumentationRegistry documentationRegistry, DeferredExecutionAwareStep<? super ExecutionRequestContext, ? extends Result> executeStep) {
        this.documentationRegistry = documentationRegistry;
        this.executeStep = executeStep;
    }

    @Override
    public Request createRequest(UnitOfWork work) {
        return new Request() {
            private String nonIncrementalReason;
            private WorkValidationContext validationContext;

            private ExecutionRequestContext createExecutionRequestContext() {
                WorkValidationContext validationContext = this.validationContext != null
                        ? this.validationContext
                        : new DefaultWorkValidationContext(documentationRegistry, work.getTypeOriginInspector());
                return new ExecutionRequestContext() {
                    @Override
                    public Optional<String> getNonIncrementalReason() {
                        return Optional.ofNullable(nonIncrementalReason);
                    }

                    @Override
                    public WorkValidationContext getValidationContext() {
                        return validationContext;
                    }
                };
            }

            @Override
            public void forceNonIncremental(String nonIncremental) {
                this.nonIncrementalReason = nonIncremental;
            }

            @Override
            public void withValidationContext(WorkValidationContext validationContext) {
                this.validationContext = validationContext;
            }

            @Override
            public Result execute() {
                return executeStep.execute(work, createExecutionRequestContext());
            }

            @Override
            public <O> CachedRequest<O> withIdentityCache(Cache<Identity, Try<O>> cache) {
                return new CachedRequest<O>() {
                    @Override
                    public <T> T getOrDeferExecution(DeferredExecutionHandler<O, T> handler) {
                        return executeStep.executeDeferred(work, createExecutionRequestContext(), cache, handler);
                    }
                };
            }
        };
    }
}