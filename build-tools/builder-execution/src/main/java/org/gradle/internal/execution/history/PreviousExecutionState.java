package org.gradle.internal.execution.history;

import com.google.common.collect.ImmutableSortedMap;
import org.gradle.internal.execution.UnitOfWork;
import org.gradle.internal.fingerprint.FileCollectionFingerprint;
import org.gradle.caching.internal.origin.OriginMetadata;;

/**
 * Captures the state a {@link UnitOfWork} after the previous execution has finished.
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