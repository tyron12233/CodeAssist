package com.tyron.builder.internal.operations;

/**
 * Constraint to apply to the execution of a {@link BuildOperation}.
 */
public enum BuildOperationConstraint {
    /**
     * Constrain execution by the configured maximum number of workers.
     */
    MAX_WORKERS,

    /**
     * Unconstrained execution allowing as many threads as required to a maximum of 10 times the configured workers.
     */
    UNCONSTRAINED
}
