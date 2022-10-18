package org.gradle.plugin.management;

import org.gradle.api.Action;
import org.gradle.api.Incubating;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.initialization.ConfigurableIncludedPluginBuild;
import org.gradle.internal.HasInternalProtocol;
import org.gradle.plugin.use.PluginDependenciesSpec;

/**
 * Configures how plugins are resolved.
 *
 * @since 3.5
 */
@HasInternalProtocol
public interface PluginManagementSpec {

    /**
     * Defines the plugin repositories to use.
     */
    void repositories(Action<? super RepositoryHandler> repositoriesAction);

    /**
     * The plugin repositories to use.
     */
    RepositoryHandler getRepositories();

    /**
     * Configure the plugin resolution strategy.
     */
    void resolutionStrategy(Action<? super PluginResolutionStrategy> action);

    /**
     * The plugin resolution strategy.
     */
    PluginResolutionStrategy getResolutionStrategy();

    /**
     * Configure the default plugin versions.
     * @since 5.6
     */
    void plugins(Action<? super PluginDependenciesSpec> action);

    /**
     * The Plugin dependencies, permitting default plugin versions to be configured.
     * @since 5.6
     */
    PluginDependenciesSpec getPlugins();

    /**
     * Includes a plugin build at the specified path to the composite build.
     * Included plugin builds can contribute settings and project plugins.
     * @param rootProject The path to the root project directory for the build.
     *
     * @since 7.0
     */
    @Incubating
    void includeBuild(String rootProject);

    /**
     * Includes a plugin build at the specified path to the composite build, with the supplied configuration.
     * Included plugin builds can contribute settings and project plugins.
     * @param rootProject The path to the root project directory for the build.
     * @param configuration An action to configure the included build.
     *
     * @since 7.0
     */
    @Incubating
    void includeBuild(String rootProject, Action<ConfigurableIncludedPluginBuild> configuration);

}
