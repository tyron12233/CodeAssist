package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.Buildable;
import com.tyron.builder.internal.HasInternalProtocol;

import java.io.File;
import java.util.Set;

/**
 * A {@code SelfResolvingDependency} is a {@link Dependency} which is able to resolve itself, independent of a
 * repository.
 */
@HasInternalProtocol
public interface SelfResolvingDependency extends Dependency, Buildable {
    /**
     * Resolves this dependency. A {@link com.tyron.builder.api.artifacts.ProjectDependency} is resolved with transitive equals true
     * by this method.
     *
     * @return The files which make up this dependency.
     * @see #resolve(boolean)
     */
    Set<File> resolve();

    /**
     * Resolves this dependency by specifying the transitive mode. This mode has only an effect if the self resolved dependency
     * is of type {@link com.tyron.builder.api.artifacts.ProjectDependency}. In this case, if transitive is <code>false</code>,
     * only the self resolving dependencies of the project configuration which are no project dependencies are resolved. If transitive
     * is set to true, other project dependencies belonging to the configuration of the resolved project dependency are
     * resolved recursively.
     *
     * @param transitive Whether to resolve transitively. Has only an effect on a {@link com.tyron.builder.api.artifacts.ProjectDependency}
     * @return The files which make up this dependency.
     */
    Set<File> resolve(boolean transitive);
}
