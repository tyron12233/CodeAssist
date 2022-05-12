package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.BuildProject;
import com.tyron.builder.internal.HasInternalProtocol;

/**
 * <p>A {@code ProjectDependency} is a {@link Dependency} on another project in the current project hierarchy.</p>
 */
@HasInternalProtocol
public interface ProjectDependency extends ModuleDependency, SelfResolvingDependency {
    /**
     * Returns the project associated with this project dependency.
     */
    BuildProject getDependencyProject();

    /**
     * {@inheritDoc}
     */
    @Override
    ProjectDependency copy();
}
