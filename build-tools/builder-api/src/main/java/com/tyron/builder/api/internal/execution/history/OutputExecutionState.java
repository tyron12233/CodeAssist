package com.tyron.builder.api.internal.execution.history;


import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.api.internal.snapshot.FileSystemSnapshot;

/**
 * Captures the state of the outputs of a {@link org.gradle.internal.execution.UnitOfWork}.
 */
public interface OutputExecutionState extends ExecutionState {

    /**
     * Snapshots of the roots of output properties.
     *
     * In the presence of overlapping outputs this might be different from
     * {@link BeforeExecutionState#getOutputFileLocationSnapshots()},
     * as this does not include overlapping outputs <em>not</em> produced by the work.
     */
    ImmutableSortedMap<String, FileSystemSnapshot> getOutputFilesProducedByWork();
}