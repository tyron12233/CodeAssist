package com.tyron.builder.api.internal.artifacts.dependencies;

import com.tyron.builder.api.artifacts.SelfResolvingDependency;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;

import javax.annotation.Nullable;

public interface SelfResolvingDependencyInternal extends SelfResolvingDependency {
    /**
     * Returns the id of the target component of this dependency, if known. If unknown, an arbitrary identifier is assigned to the files referenced by this dependency.
     */
    @Nullable
    ComponentIdentifier getTargetComponentId();
}
