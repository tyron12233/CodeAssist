package org.gradle.internal.execution.history.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import org.gradle.internal.execution.history.PreviousExecutionState;
import org.gradle.internal.fingerprint.FileCollectionFingerprint;
import org.gradle.internal.snapshot.FileSystemSnapshot;
import org.gradle.internal.snapshot.ValueSnapshot;
import org.gradle.internal.snapshot.impl.ImplementationSnapshot;
import org.gradle.caching.internal.origin.OriginMetadata;

public class DefaultPreviousExecutionState extends AbstractInputExecutionState<FileCollectionFingerprint> implements PreviousExecutionState {
    private final ImmutableSortedMap<String, FileSystemSnapshot> outputFilesProducedByWork;
    private final OriginMetadata originMetadata;
    private final boolean successful;

    public DefaultPreviousExecutionState(
            OriginMetadata originMetadata,
            ImplementationSnapshot implementation,
            ImmutableList<ImplementationSnapshot> additionalImplementations,
            ImmutableSortedMap<String, ValueSnapshot> inputProperties,
            ImmutableSortedMap<String, FileCollectionFingerprint> inputFileProperties,
            ImmutableSortedMap<String, FileSystemSnapshot> outputFilesProducedByWork,
            boolean successful
    ) {
        super(implementation, additionalImplementations, inputProperties, inputFileProperties);
        this.outputFilesProducedByWork = outputFilesProducedByWork;
        this.originMetadata = originMetadata;
        this.successful = successful;
    }

    @Override
    public ImmutableSortedMap<String, FileSystemSnapshot> getOutputFilesProducedByWork() {
        return outputFilesProducedByWork;
    }

    @Override
    public OriginMetadata getOriginMetadata() {
        return originMetadata;
    }

    @Override
    public boolean isSuccessful() {
        return successful;
    }
}