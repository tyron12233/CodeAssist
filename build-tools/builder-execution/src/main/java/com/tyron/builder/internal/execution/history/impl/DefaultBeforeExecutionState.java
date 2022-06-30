package com.tyron.builder.internal.execution.history.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.internal.execution.history.BeforeExecutionState;
import com.tyron.builder.internal.execution.history.OverlappingOutputs;
import com.tyron.builder.internal.fingerprint.CurrentFileCollectionFingerprint;
import com.tyron.builder.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.internal.snapshot.ValueSnapshot;
import com.tyron.builder.internal.snapshot.impl.ImplementationSnapshot;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public class DefaultBeforeExecutionState extends AbstractInputExecutionState<CurrentFileCollectionFingerprint> implements BeforeExecutionState {
    @Nullable
    private final OverlappingOutputs detectedOutputOverlaps;
    private final ImmutableSortedMap<String, FileSystemSnapshot> outputFileLocationSnapshots;

    public DefaultBeforeExecutionState(
            ImplementationSnapshot implementation,
            ImmutableList<ImplementationSnapshot> additionalImplementations,
            ImmutableSortedMap<String, ValueSnapshot> inputProperties,
            ImmutableSortedMap<String, CurrentFileCollectionFingerprint> inputFileProperties,
            ImmutableSortedMap<String, FileSystemSnapshot> outputFileLocationSnapshots,
            @Nullable OverlappingOutputs detectedOutputOverlaps
    ) {
        super(
                implementation,
                additionalImplementations,
                inputProperties,
                inputFileProperties
        );
        this.outputFileLocationSnapshots = outputFileLocationSnapshots;
        this.detectedOutputOverlaps = detectedOutputOverlaps;
    }

    @Override
    public ImmutableSortedMap<String, FileSystemSnapshot> getOutputFileLocationSnapshots() {
        return outputFileLocationSnapshots;
    }

    @Override
    public Optional<OverlappingOutputs> getDetectedOverlappingOutputs() {
        return Optional.ofNullable(detectedOutputOverlaps);
    }
}