package org.gradle.internal.execution.history;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import org.gradle.internal.fingerprint.FileCollectionFingerprint;
import org.gradle.internal.snapshot.ValueSnapshot;
import org.gradle.internal.snapshot.impl.ImplementationSnapshot;

/**
 * Captures the state of the inputs of a {@link org.gradle.internal.execution.UnitOfWork}.
 */
public interface InputExecutionState extends ExecutionState {
    /**
     * The main implementation snapshots.
     */
    ImplementationSnapshot getImplementation();

    /**
     * Used only for tasks to return all the task actions.
     */
    ImmutableList<ImplementationSnapshot> getAdditionalImplementations();

    /**
     * The non-file inputs.
     */
    ImmutableSortedMap<String, ValueSnapshot> getInputProperties();

    /**
     * The file inputs.
     */
    ImmutableSortedMap<String, ? extends FileCollectionFingerprint> getInputFileProperties();
}