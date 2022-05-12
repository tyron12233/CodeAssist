package com.tyron.builder.api.artifacts;

/**
 * <p>A {@code ModuleDependency} is a {@link Dependency} on a module outside the current project hierarchy.</p>
 */
public interface ExternalModuleDependency extends ExternalDependency {
    /**
     * Returns whether or not Gradle should always check for a change in the remote repository.
     *
     * @see #setChanging(boolean)
     */
    boolean isChanging();

    /**
     * Sets whether or not Gradle should always check for a change in the remote repository. If set to true, Gradle will
     * check the remote repository even if a dependency with the same version is already in the local cache. Defaults to
     * false.
     *
     * @param changing Whether or not Gradle should always check for a change in the remote repository
     * @return this
     */
    ExternalModuleDependency setChanging(boolean changing);

    /**
     * {@inheritDoc}
     */
    @Override
    ExternalModuleDependency copy();
}
