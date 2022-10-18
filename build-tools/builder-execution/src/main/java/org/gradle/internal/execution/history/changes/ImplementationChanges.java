package org.gradle.internal.execution.history.changes;

import com.google.common.collect.ImmutableList;
import org.gradle.api.Describable;
import org.gradle.internal.execution.history.DescriptiveChange;
import org.gradle.internal.snapshot.impl.ClassImplementationSnapshot;
import org.gradle.internal.snapshot.impl.ImplementationSnapshot;
import org.gradle.internal.snapshot.impl.UnknownImplementationSnapshot;

public class ImplementationChanges implements ChangeContainer {
    private final ImplementationSnapshot previousImplementation;
    private final ImmutableList<ImplementationSnapshot> previousAdditionalImplementations;
    private final ClassImplementationSnapshot currentImplementation;
    private final ImmutableList<ImplementationSnapshot> currentAdditionalImplementations;
    private final Describable executable;

    public ImplementationChanges(
            ImplementationSnapshot previousImplementation,
            ImmutableList<ImplementationSnapshot> previousAdditionalImplementations,
            ClassImplementationSnapshot currentImplementation,
            ImmutableList<ImplementationSnapshot> currentAdditionalImplementations,
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
        if (!currentImplementation.getClassIdentifier().equals(previousImplementation.getClassIdentifier())) {
            return visitor.visitChange(new DescriptiveChange("The type of %s has changed from '%s' to '%s'.",
                    executable.getDisplayName(), previousImplementation.getClassIdentifier(), currentImplementation.getClassIdentifier()));
        }

        if (previousImplementation instanceof UnknownImplementationSnapshot) {
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