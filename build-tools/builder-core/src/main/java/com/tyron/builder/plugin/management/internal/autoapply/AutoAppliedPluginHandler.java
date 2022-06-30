package com.tyron.builder.plugin.management.internal.autoapply;

import com.tyron.builder.plugin.management.internal.PluginRequests;

/**
 * <p>
 * Certain plugins are important enough that Gradle should auto-apply them when it is clear
 * that the user is trying to use it. For instance, when the user uses the <code>--scan</code> option, it
 * is clear they expect the build scan plugin to be applied.
 * </p>
 *
 * Auto-application of a plugin is skipped in the following situations, so the user can adjust the version they want:
 *
 * <ul>
 * <li> The plugin was already applied (e.g. through an init script)
 * <li> The plugin was already requested in the <code>plugins {}</code> block </li>
 * <li> The plugin was already requested in the <code>buildscript {}</code> block </li>
 *</ul>
 */
public interface AutoAppliedPluginHandler {

    /**
     * Merges the provided user requests with other plugin requests that should be auto-applied
     * based on the current build invocation and the given target.
     */
    PluginRequests mergeWithAutoAppliedPlugins(PluginRequests initialRequests, Object pluginTarget);
}
