package com.tyron.builder.internal.execution.steps;

import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.internal.execution.UnitOfWork.Identity;
import com.tyron.builder.internal.fingerprint.CurrentFileCollectionFingerprint;
import com.tyron.builder.internal.snapshot.ValueSnapshot;

public interface IdentityContext extends ExecutionRequestContext {
    /**
     * All currently known input properties.
     */
    ImmutableSortedMap<String, ValueSnapshot> getInputProperties();

    /**
     * All currently known input file properties.
     */
    ImmutableSortedMap<String, CurrentFileCollectionFingerprint> getInputFileProperties();

    /**
     * Returns an identity for the given work item that uniquely identifies it
     * among all the other work items of the same type in the current build.
     */
    Identity getIdentity();
}