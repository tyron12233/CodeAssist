package org.gradle.api.internal.artifacts.dependencies;

import org.gradle.api.artifacts.SelfResolvingDependency;
import org.gradle.api.artifacts.component.ComponentIdentifier;

import javax.annotation.Nullable;

public interface SelfResolvingDependencyInternal extends SelfResolvingDependency {
    /**
     * Returns the id of the target component of this dependency, if known. If unknown, an arbitrary identifier is assigned to the files referenced by this dependency.
     */
    @Nullable
    ComponentIdentifier getTargetComponentId();
}
