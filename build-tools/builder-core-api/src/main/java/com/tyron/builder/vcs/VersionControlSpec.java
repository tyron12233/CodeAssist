package com.tyron.builder.vcs;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Describable;
import com.tyron.builder.api.initialization.definition.InjectedPluginDependencies;

/**
 * Captures user-provided information about a version control repository.
 *
 * @since 4.4
 */
public interface VersionControlSpec extends Describable {
    /**
     * Returns a {@link String} identifier which will be unique to this version
     * control specification among other version control specifications.
     */
    String getUniqueId();

    /**
     * Returns the name of the repository.
     */
    String getRepoName();

    /**
     * Returns the relative path to the root of the build within the repository.
     *
     * <p>Defaults to an empty relative path, meaning the root of the repository.
     *
     * @return the root directory of the build, relative to the root of this repository.
     * @since 4.5
     */
    String getRootDir();

    /**
     * Sets the relative path to the root of the build within the repository. Use an empty string to refer to the root of the repository.
     *
     * @param rootDir The root directory of the build, relative to the root of this repository.
     * @since 4.5
     */
    void setRootDir(String rootDir);

    /**
     * Configure injected plugins into this build.
     *
     * @param configuration the configuration action for adding injected plugins
     * @since 4.6
     */
    void plugins(Action<? super InjectedPluginDependencies> configuration);
}
