package com.tyron.builder.plugin.use;

import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.provider.Provider;

import javax.annotation.Nullable;

/**
 * A mutable specification of a dependency on a plugin.
 * <p>
 * Can be used to specify the version of the plugin to use.
 * </p>
 * <p>
 * See {@link PluginDependenciesSpec} for more information about declaring plugin dependencies.
 * </p>
 */
public interface PluginDependencySpec {

    /**
     * Specify the version of the plugin to depend on.
     *
     * <pre>
     * plugins {
     *     id "org.company.myplugin" version "1.0"
     * }
     * </pre>
     * <p>
     * By default, dependencies have no (i.e. {@code null}) version.
     * </p>
     * Core plugins must not include a version number specification.
     * Community plugins must include a version number specification.
     *
     * @param version the version string ({@code null} for no specified version, which is the default)
     * @return this
     */
    PluginDependencySpec version(@Nullable String version);

    /**
     * Specify the version of the plugin to depend on.
     *
     * <pre>
     * plugins {
     *     id "org.company.myplugin" version libs.versions.myplugin
     * }
     * </pre>
     *
     * @param version the version provider, for example as found in a version catalog
     * @return this
     *
     * @since 7.2
     */
    @Incubating
    default PluginDependencySpec version(Provider<String> version) {
        // providers used in plugins block are necessarily at configuration time
        return this.version(version.get());
    }

    /**
     * Specifies whether the plugin should be applied to the current project. Otherwise it is only put
     * on the project's classpath.
     * <p>
     * This is useful when reusing classes from a plugin or to apply a plugin to sub-projects:
     *
     * <pre>
     * plugins {
     *     id "org.company.myplugin" version "1.0" apply false
     * }
     *
     * subprojects {
     *     if (someCondition) {
     *         apply plugin: "org.company.myplugin"
     *     }
     * }
     * </pre>
     *
     * @param apply whether to apply the plugin to the current project or not. Defaults to true
     * @return this
     */
    PluginDependencySpec apply(boolean apply);

}
