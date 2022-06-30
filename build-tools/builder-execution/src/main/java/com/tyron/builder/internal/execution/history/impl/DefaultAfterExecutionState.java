package com.tyron.builder.internal.execution.history.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.internal.execution.history.AfterExecutionState;
import com.tyron.builder.internal.execution.history.BeforeExecutionState;
import com.tyron.builder.internal.fingerprint.CurrentFileCollectionFingerprint;
import com.tyron.builder.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.internal.snapshot.ValueSnapshot;
import com.tyron.builder.internal.snapshot.impl.ImplementationSnapshot;
import com.tyron.builder.caching.internal.origin.OriginMetadata;

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
