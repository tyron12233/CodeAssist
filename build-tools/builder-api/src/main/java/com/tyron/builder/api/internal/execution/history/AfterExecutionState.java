package com.tyron.builder.api.internal.execution.history;

import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.api.internal.fingerprint.CurrentFileCollectionFingerprint;
import com.tyron.builder.api.internal.origin.OriginMetadata;

/**
 * Captures the state of a {@link org.gradle.internal.execution.UnitOfWork} after it has been executed.
 *
 * Execution here might also mean being up-to-date or loaded from cache.
 */
public interface AfterExecutionState extends InputExecutionState, OutputExecutionState {
    @Override
    ImmutableSortedMap<String, CurrentFileCollectionFingerprint> getInputFileProperties();

    /**
     * The origin metadata of the outputs captured.
     *
     * This might come from the current execution, or a previous one.
     *
     * @see #isReused()
     */
    OriginMetadata getOriginMetadata();

    /**
     * Whether the outputs come from a previous execution.
     */
    boolean isReused();
}