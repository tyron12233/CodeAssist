package com.tyron.builder.api.plugins;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Plugin;
import com.tyron.builder.internal.HasInternalProtocol;

import javax.annotation.Nullable;

/**
 * Facilitates applying plugins and determining which plugins have been applied to a {@link PluginAware} object.
 *
 * @see PluginAware
 * @since 2.3
 */
@HasInternalProtocol
//@NonExtensible
public interface PluginManager {

    /**
     * Applies the plugin with the given ID. Does nothing if the plugin has already been applied.
     * <p>
     * Plugins in the {@code "org.gradle"} namespace can be applied directly via name.
     * That is, the following two lines are equivalent…
     * <pre class='autoTested'>
     * pluginManager.apply "org.gradle.java"
     * pluginManager.apply "java"
     * </pre>
     *
     * @param pluginId the ID of the plugin to apply
     * @since 2.3
     */
    void apply(String pluginId);

    /**
     * Applies the given plugin. Does nothing if the plugin has already been applied.
     * <p>
     * The given class should implement the {@link Plugin} interface, and be parameterized for a compatible type of {@code this}.
     * <p>
     * The following two lines are equivalent…
     * <pre class='autoTested'>
     * pluginManager.apply org.gradle.api.plugins.JavaPlugin
     * pluginManager.apply "org.gradle.java"
     * </pre>
     *
     * @param type the plugin class to apply
     * @since 2.3
     */
    void apply(Class<?> type);

    /**
     * Returns the information about the plugin that has been applied with the given ID, or null if no plugin has been applied with the given ID.
     * <p>
     * Plugins in the {@code "org.gradle"} namespace (that is, core Gradle plugins) can be specified by either name (e.g. {@code "java"}) or ID {@code "org.gradle.java"}.
     * All other plugins must be queried for by their full ID (e.g. {@code "org.company.some-plugin"}).
     * <p>
     * Some Gradle plugins have not yet migrated to fully qualified plugin IDs.
     * Such plugins can be detected with this method by simply using the unqualified ID (e.g. {@code "some-third-party-plugin"}.
     *
     * @param id the plugin ID
     * @return information about the applied plugin, or {@code null} if no plugin has been applied with the given ID
     * @since 2.3
     */
    @Nullable
    AppliedPlugin findPlugin(String id);

    /**
     * Returns {@code true} if a plugin with the given ID has already been applied, otherwise {@code false}.
     *
     * @param id the plugin ID. See {@link #findPlugin(String)} for details about this parameter.
     * @return {@code true} if the plugin has been applied
     * @since 2.3
     */
    boolean hasPlugin(String id);

    /**
     * Executes the given action when the specified plugin is applied.
     * <p>
     * If a plugin with the specified ID has already been applied, the supplied action will be executed immediately.
     * Otherwise, the action will executed immediately after a plugin with the specified ID is applied.
     * <p>
     * The given action is always executed after the plugin has been applied.
     *
     * @param id the plugin ID. See {@link #findPlugin(String)} for details about this parameter.
     * @param action the action to execute if/when the plugin is applied
     * @since 2.3
     */
    void withPlugin(String id, Action<? super AppliedPlugin> action);

}
