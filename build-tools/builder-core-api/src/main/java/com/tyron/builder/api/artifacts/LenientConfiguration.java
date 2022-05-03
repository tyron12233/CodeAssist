package com.tyron.builder.api.artifacts;

import java.io.File;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Resolved configuration that does not fail eagerly when some dependencies are not resolved, or some artifacts do not exist.
 */
public interface LenientConfiguration {
    /**
     * Returns successfully resolved direct dependencies.
     *
     * @return only resolved dependencies
     * @since 3.3
     */
    Set<ResolvedDependency> getFirstLevelModuleDependencies();

    /**
     * Returns successfully resolved dependencies that match the given spec.
     *
     * @param dependencySpec dependency spec
     * @return only resolved dependencies
     */
    Set<ResolvedDependency> getFirstLevelModuleDependencies(Predicate<? super Dependency> dependencySpec);

    /**
     * Returns all successfully resolved dependencies including transitive dependencies.
     *
     * @since 3.1
     * @return all resolved dependencies
     */
    Set<ResolvedDependency> getAllModuleDependencies();

    /**
     * returns dependencies that were attempted to resolve but failed.
     * If empty then all dependencies are neatly resolved.
     *
     * @return only unresolved dependencies
     */
    Set<UnresolvedDependency> getUnresolvedModuleDependencies();

    /**
     * Returns successfully resolved files. Ignores dependencies or files that cannot be resolved.
     *
     * @return resolved dependencies files
     * @since 3.3
     */
    Set<File> getFiles();

    /**
     * Returns successfully resolved files. Ignores dependencies or files that cannot be resolved.
     *
     * @param dependencySpec dependency spec
     * @return resolved dependencies files
     */
    Set<File> getFiles(Predicate<? super Dependency> dependencySpec);

    /**
     * Gets successfully resolved artifacts. Ignores dependencies or files that cannot be resolved.
     *
     * @return successfully resolved artifacts
     * @since 3.3
     */
    Set<ResolvedArtifact> getArtifacts();

    /**
     * Gets successfully resolved artifacts. Ignores dependencies or files that cannot be resolved.
     *
     * @param dependencySpec dependency spec
     * @return successfully resolved artifacts for dependencies that match given dependency spec
     */
    Set<ResolvedArtifact> getArtifacts(Predicate<? super Dependency> dependencySpec);
}
