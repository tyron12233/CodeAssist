package org.gradle.internal.execution.history;

import com.google.common.collect.ImmutableSortedMap;
import org.gradle.internal.execution.UnitOfWork;
import org.gradle.internal.execution.steps.AfterExecutionResult;
import org.gradle.internal.fingerprint.CurrentFileCollectionFingerprint;
import org.gradle.internal.snapshot.FileSystemSnapshot;

import java.util.Optional;

/**
 * Captures the state of a {@link UnitOfWork} before execution.
 */
public interface BeforeExecutionState extends InputExecutionState {
    /**
     * {@inheritDoc}
     */
    @Override
    ImmutableSortedMap<String, CurrentFileCollectionFingerprint> getInputFileProperties();

    /**
     * Snapshots of the roots of output properties.
     *
     * This includes snapshots for the whole output {@link o==FileCollection}.
     *
     * @see PreviousExecutionState#getOutputFilesProducedByWork()
     * @see AfterExecutionResult#getAfterExecutionState()
     */
    ImmutableSortedMap<String, FileSystemSnapshot> getOutputFileLocationSnapshots();

    /**
     * Returns overlapping outputs if they are detected.
     *
     * @see UnitOfWork#getOverlappingOutputHandling()
     */
    Optional<OverlappingOutputs> getDetectedOverlappingOutputs();
}