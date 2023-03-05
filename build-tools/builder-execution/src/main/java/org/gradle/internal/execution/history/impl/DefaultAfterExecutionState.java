package org.gradle.internal.execution.history.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import org.gradle.internal.execution.history.AfterExecutionState;
import org.gradle.internal.execution.history.BeforeExecutionState;
import org.gradle.internal.fingerprint.CurrentFileCollectionFingerprint;
import org.gradle.internal.snapshot.FileSystemSnapshot;
import org.gradle.internal.snapshot.ValueSnapshot;
import org.gradle.internal.snapshot.impl.ImplementationSnapshot;
import org.gradle.caching.internal.origin.OriginMetadata;

public class DefaultAfterExecutionState implements AfterExecutionState {
    private final BeforeExecutionState beforeExecutionState;
    private final ImmutableSortedMap<String, FileSystemSnapshot> outputFileLocationSnapshots;
    private final OriginMetadata originMetadata;
    private final boolean reused;

    public DefaultAfterExecutionState(
            BeforeExecutionState beforeExecutionState,
            ImmutableSortedMap<String, FileSystemSnapshot> outputFileLocationSnapshots,
            OriginMetadata originMetadata,
            boolean reused
    ) {
        this.beforeExecutionState = beforeExecutionState;
        this.outputFileLocationSnapshots = outputFileLocationSnapshots;
        this.originMetadata = originMetadata;
        this.reused = reused;
    }

    @Override
    public ImmutableSortedMap<String, CurrentFileCollectionFingerprint> getInputFileProperties() {
        return beforeExecutionState.getInputFileProperties();
    }

    @Override
    public ImplementationSnapshot getImplementation() {
        return beforeExecutionState.getImplementation();
    }

    @Override
    public ImmutableList<ImplementationSnapshot> getAdditionalImplementations() {
        return beforeExecutionState.getAdditionalImplementations();
    }

    @Override
    public ImmutableSortedMap<String, ValueSnapshot> getInputProperties() {
        return beforeExecutionState.getInputProperties();
    }

    @Override
    public ImmutableSortedMap<String, FileSystemSnapshot> getOutputFilesProducedByWork() {
        return outputFileLocationSnapshots;
    }

    @Override
    public OriginMetadata getOriginMetadata() {
        return originMetadata;
    }

    @Override
    public boolean isReused() {
        return reused;
    }
}
