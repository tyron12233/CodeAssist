package com.tyron.builder.api.internal.execution;

public enum ExecutionOutcome {
    /**
     * The outputs haven't been changed, because the work is already up-to-date
     * (i.e. its inputs and outputs match that of the previous execution in the
     * same workspace).
     */
    UP_TO_DATE,

    /**
     * The outputs of the work have been loaded from the build cache.
     */
    FROM_CACHE,

    /**
     * Executing the work was not necessary to produce the outputs.
     * This is usually due to the work having no inputs to process.
     */
    SHORT_CIRCUITED,

    /**
     * The work has been executed with information about the changes that happened since the previous execution.
     */
    EXECUTED_INCREMENTALLY,

    /**
     * The work has been executed with no incremental change information.
     */
    EXECUTED_NON_INCREMENTALLY
}