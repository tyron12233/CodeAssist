package com.tyron.builder.api.internal.execution.history;

import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.api.internal.fingerprint.FileCollectionFingerprint;
import com.tyron.builder.api.internal.origin.OriginMetadata;

/**
 * Captures the state a {@link org.gradle.internal.execution.UnitOfWork} after the previous execution has finished.
 */
public interface PreviousExecutionState extends InputExecutionState, OutputExecutionState {

    /**
     * The ID and execution time of origin of the execution's outputs.
     */
    OriginMetadata getOriginMetadata();

    /**
     * Whether the execution was successful.
     */
    boolean isSuccessful();

    /**
     * {@inheritDoc}
     */
    @Override
    ImmutableSortedMap<String, FileCollectionFingerprint> getInputFileProperties();
}