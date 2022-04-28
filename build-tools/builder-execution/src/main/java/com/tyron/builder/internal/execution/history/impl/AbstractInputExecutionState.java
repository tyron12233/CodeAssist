package com.tyron.builder.internal.execution.history.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.internal.execution.history.InputExecutionState;
import com.tyron.builder.internal.fingerprint.FileCollectionFingerprint;
import com.tyron.builder.internal.snapshot.ValueSnapshot;
import com.tyron.builder.internal.snapshot.impl.ImplementationSnapshot;

public class AbstractInputExecutionState<C extends FileCollectionFingerprint> implements InputExecutionState {
    protected final ImplementationSnapshot implementation;
    protected final ImmutableList<ImplementationSnapshot> additionalImplementations;
    protected final ImmutableSortedMap<String, ValueSnapshot> inputProperties;
    protected final ImmutableSortedMap<String, C> inputFileProperties;

    public AbstractInputExecutionState(
            ImplementationSnapshot implementation,
            ImmutableList<ImplementationSnapshot> additionalImplementations,
            ImmutableSortedMap<String, ValueSnapshot> inputProperties,
            ImmutableSortedMap<String, C> inputFileProperties
    ) {
        this.implementation = implementation;
        this.additionalImplementations = additionalImplementations;
        this.inputProperties = inputProperties;
        this.inputFileProperties = inputFileProperties;
    }

    @Override
    public ImplementationSnapshot getImplementation() {
        return implementation;
    }

    @Override
    public ImmutableList<ImplementationSnapshot> getAdditionalImplementations() {
        return additionalImplementations;
    }

    @Override
    public ImmutableSortedMap<String, ValueSnapshot> getInputProperties() {
        return inputProperties;
    }

    @Override
    public ImmutableSortedMap<String, C> getInputFileProperties() {
        return inputFileProperties;
    }
}