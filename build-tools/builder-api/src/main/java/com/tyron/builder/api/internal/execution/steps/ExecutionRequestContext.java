package com.tyron.builder.api.internal.execution.steps;

import com.tyron.builder.api.internal.execution.WorkValidationContext;

import java.util.Optional;

public interface ExecutionRequestContext extends Context {
    /**
     * If incremental mode is disabled, this returns the reason, otherwise it's empty.
     */
    Optional<String> getNonIncrementalReason();

    /**
     * The validation context to use during the execution of the work.
     */
    WorkValidationContext getValidationContext();
}