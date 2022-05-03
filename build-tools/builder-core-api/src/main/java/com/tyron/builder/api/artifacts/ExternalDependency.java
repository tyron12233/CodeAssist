package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.Action;

/**
 * <p>An {@code ExternalDependency} is a {@link Dependency} on a source outside the current project hierarchy.</p>
 */
public interface ExternalDependency extends ModuleDependency, ModuleVersionSelector {

    /**
     * Returns whether or not the version of this dependency should be enforced in the case of version conflicts.
     */
    boolean isForce();

    /**
     * Sets whether or not the version of this dependency should be enforced in the case of version conflicts.
     *
     * @param force Whether to force this version or not.
     * @return this
     *
     * @deprecated Use {@link MutableVersionConstraint#strictly(String) instead.}
     */
    @Deprecated
    ExternalDependency setForce(boolean force);

    /**
     * {@inheritDoc}
     */
    @Override
    ExternalDependency copy();

    /**
     * Configures the version constraint for this dependency.
     * @param configureAction the configuration action for the module version
     * @since 4.4
     */
    void version(Action<? super MutableVersionConstraint> configureAction);

    /**
     * Returns the version constraint to be used during selection.
     * @return the version constraint
     *
     * @since 4.4
     */
    VersionConstraint getVersionConstraint();
}
