package com.tyron.builder.api.initialization.dsl;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.Named;
import com.tyron.builder.api.artifacts.MutableVersionConstraint;
import com.tyron.builder.api.provider.Property;
import com.tyron.builder.internal.HasInternalProtocol;

import java.util.List;

/**
 * A version catalog builder. Dependencies defined via this model
 * will trigger the generation of accessors available in build scripts.
 *
 * @since 7.0
 */
@Incubating
@HasInternalProtocol
public interface VersionCatalogBuilder extends Named {

    /**
     * A description for the dependencies model, which will be used in
     * the generated sources as documentation.
     * @return the description for this model
     */
    Property<String> getDescription();

    /**
     * Configures the model by reading it from a version catalog.
     * A version catalog is a component published using the `version-catalog` plugin or
     * a local TOML file.
     *
     * All imports configured by this method will be accumulated in order and executed
     * before any other modification provided by this builder, such that "local" modifications
     * have higher priority than any imported component.
     *
     * @param dependencyNotation any notation supported by {@link com.tyron.builder.api.artifacts.dsl.DependencyHandler}
     */
    void from(Object dependencyNotation);

    /**
     * Configures a dependency version which can then be referenced using
     * the {@link VersionCatalogBuilder.LibraryAliasBuilder#versionRef(String)} )} method.
     *
     * @param name an identifier for the version
     * @param versionSpec the dependency version spec
     * @return the version alias name
     */
    String version(String name, Action<? super MutableVersionConstraint> versionSpec);

    /**
     * Configures a dependency version which can then be referenced using
     * the {@link VersionCatalogBuilder.LibraryAliasBuilder#versionRef(String)} method.
     *
     * @param name an identifier for the version
     * @param version the version string
     */
    String version(String name, String version);

    /**
     * Entry point for registering an alias for a library
     * @param alias the alias identifer
     * @return a builder for this alias
     */
    AliasBuilder alias(String alias);

    /**
     * Declares a bundle of dependencies. A bundle consists of a name for the bundle,
     * and a list of aliases. The aliases must correspond to aliases defined via
     * the {@link #alias(String)} method.
     *
     * @param name the name of the bundle
     * @param aliases the aliases of the dependencies included in the bundle
     */
    void bundle(String name, List<String> aliases);

    /**
     * Returns the name of the extension configured by this builder
     */
    String getLibrariesExtensionName();

    /**
     * Allows configuring an alias
     *
     * @since 7.0
     */
    @Incubating
    interface AliasBuilder {
        /**
         * Sets GAV coordinates for this alias
         * @param groupArtifactVersion the GAV coordinates, in the group:artifact:version form
         */
        void to(String groupArtifactVersion);

        /**
         * Sets the group and name of this alias
         * @param group the group
         * @param name the name (or artifact id)
         * @return a builder to configure the version
         */
        LibraryAliasBuilder to(String group, String name);

        /**
         * Sets the plugin id this alias will reference
         * @param id the plugin id
         * @return a builder to configure the plugin
         *
         * @since 7.2
         */
        PluginAliasBuilder toPluginId(String id);
    }

    /**
     * Allows configuring the version of a library
     *
     * @since 7.0
     */
    @Incubating
    interface LibraryAliasBuilder {
        /**
         * Configures the version for this alias
         */
        void version(Action<? super MutableVersionConstraint> versionSpec);

        /**
         * Configures the required version for this alias
         */
        void version(String version);

        /**
         * Configures this alias to use a version reference, created
         * via the {@link #version(String, Action)} method.
         *
         * @param versionRef the version reference
         */
        void versionRef(String versionRef);

        /**
         * Do not associate this alias to a particular version, in which
         * case the dependency notation will just have group and artifact.
         *
         */
        void withoutVersion();
    }

    /**
     * Allows configuring the version of a plugin
     *
     * @since 7.2
     */
    @Incubating
    interface PluginAliasBuilder {
        /**
         * Configures the version for this alias
         */
        void version(Action<? super MutableVersionConstraint> versionSpec);

        /**
         * Configures the required version for this alias
         */
        void version(String version);

        /**
         * Configures this alias to use a version reference, created
         * via the {@link #version(String, Action)} method.
         *
         * @param versionRef the version reference
         */
        void versionRef(String versionRef);
    }

}
