package com.tyron.builder.internal.execution.history.changes;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.Describable;
import com.tyron.builder.internal.execution.history.DescriptiveChange;
import com.tyron.builder.internal.snapshot.impl.ImplementationSnapshot;
import com.tyron.builder.internal.snapshot.impl.KnownImplementationSnapshot;

public class ImplementationChanges implements ChangeContainer {
    private final ImplementationSnapshot previousImplementation;
    private final ImmutableList<ImplementationSnapshot> previousAdditionalImplementations;
    private final KnownImplementationSnapshot currentImplementation;
    private final ImmutableList<KnownImplementationSnapshot> currentAdditionalImplementations;
    private final Describable executable;

    public ImplementationChanges(
            ImplementationSnapshot previousImplementation,
            ImmutableList<ImplementationSnapshot> previousAdditionalImplementations,
            KnownImplementationSnapshot currentImplementation,
            ImmutableList<KnownImplementationSnapshot> currentAdditionalImplementations,
            Describable executable
    ) {
        this.previousImplementation = previousImplementation;
        this.previousAdditionalImplementations = previousAdditionalImplementations;
        this.currentImplementation = currentImplementation;
        this.currentAdditionalImplementations = currentAdditionalImplementations;
        this.executable = executable;
    }

    @Override
    public boolean accept(ChangeVisitor visitor) {
        if (!currentImplementation.getTypeName().equals(previousImplementation.getTypeName())) {
            return visitor.visitChange(new DescriptiveChange("The type of %s has changed from '%s' to '%s'.",
                    executable.getDisplayName(), previousImplementation.getTypeName(), currentImplementation.getTypeName()));
        }
        if (previousImplementation.isUnknown()) {
            return visitor.visitChange(new DescriptiveChange("The implementation of %s has changed.",
                    executable.getDisplayName())
            );
        }
        if (!currentImplementation.getClassLoaderHash().equals(previousImplementation.getClassLoaderHash())) {
            return visitor.visitChange(new DescriptiveChange("Class path of %s has changed from %s to %s.",
                    executable.getDisplayName(), previousImplementation.getClassLoaderHash(), currentImplementation.getClassLoaderHash()));
        }

        if (!currentAdditionalImplementations.equals(previousAdditionalImplementations)) {
            return visitor.visitChange(new DescriptiveChange("One or more additional actions for %s have changed.",
                    executable.getDisplayName()));
        }
        return true;
    }
}