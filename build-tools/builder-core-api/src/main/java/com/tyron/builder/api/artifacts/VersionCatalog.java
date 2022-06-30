package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.Named;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.plugin.use.PluginDependency;

import java.util.List;
import java.util.Optional;

/**
 * Provides access to a version catalog. Unlike generated extension
 * classes for catalogs, there is no guarantee that the requested
 * aliases exist so you must check existence on the returned optional
 * values.
 *
 * @since 7.0
 */
@Incubating
//@NonNullApi
public interface VersionCatalog extends Named {
    /**
     * Returns the dependency provider for the corresponding alias.
     * <p>
     * Note: Alias will be automatically normalized: '-', '_' and '.' will be replaced with '.'
     * </p>
     * @param alias the alias of the dependency
     */
    Optional<Provider<MinimalExternalModuleDependency>> findDependency(String alias);

    /**
     * Returns the dependency provider for the corresponding bundle.
     * <p>
     * Note: Bundle will be automatically normalized: '-', '_' and '.' will be replaced with '.'
     * </p>
     * @param bundle the alias of the bundle
     */
    Optional<Provider<ExternalModuleDependencyBundle>> findBundle(String bundle);

    /**
     * Returns the version constraint with the corresponding name in the catalog.
     * <p>
     * Note: Name will be automatically normalized: '-', '_' and '.' will be replaced with '.'
     * </p>
     * @param name the name of the version
     */
    Optional<VersionConstraint> findVersion(String name);

    /**
     * Returns the plugin dependency provider for the requested alias.
     * <p>
     * Note: Alias will be automatically normalized: '-', '_' and '.' will be replaced with '.'
     * </p>
     * @param alias the alias of the plugin
     *
     * @since 7.2
     */
    Optional<Provider<PluginDependency>> findPlugin(String alias);

    /**
     * Returns the list of aliases defined in this version catalog.
     * @return the list of dependency aliases
     *
     * @since 7.1
     */
    List<String> getDependencyAliases();

    /**
     * Returns the list of bundles defined in this version catalog.
     * @return the list of bundle aliases
     *
     * @since 7.1
     */
    List<String> getBundleAliases();

    /**
     * Returns the list of version aliases defined in this version catalog.
     * @return the list of version aliases
     *
     * @since 7.1
     */
    List<String> getVersionAliases();

    /**
     * Returns the list of plugin aliases defined in this version catalog.
     * @return the list of plugin aliases
     *
     * @since 7.2
     */
    List<String> getPluginAliases();
}
