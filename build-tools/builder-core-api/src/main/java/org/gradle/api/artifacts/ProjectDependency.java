package org.gradle.api.artifacts;

import org.gradle.api.BuildProject;
import org.gradle.internal.HasInternalProtocol;

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
