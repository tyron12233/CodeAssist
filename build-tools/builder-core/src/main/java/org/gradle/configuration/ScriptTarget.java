package org.gradle.configuration;

import org.gradle.api.internal.plugins.PluginManagerInternal;
import org.gradle.groovy.scripts.BasicScript;
import org.gradle.groovy.scripts.internal.Permits;

import groovy.lang.Script;

/**
 * A view over the target of a script. Represents the DSL that will be applied to the target.
 */
public interface ScriptTarget {
    /**
     * Returns a unique id for the DSL, used in paths and such.
     */
    String getId();

    /**
     * Attaches the target's main script to the target, if it needs it
     */
    void attachScript(Script script);

    String getClasspathBlockName();

    Class<? extends BasicScript> getScriptClass();

    boolean getSupportsPluginsBlock();

    boolean getSupportsPluginManagementBlock();

    boolean getSupportsMethodInheritance();

    PluginManagerInternal getPluginManager();

    /**
     * Add a configuration action to be applied to the target.
     *
     * @param runnable The action. Should be run in the order provided.
     * @param deferrable true when the action can be deferred
     */
    void addConfiguration(Runnable runnable, boolean deferrable);

    default Permits getPluginsBlockPermits() {
        return Permits.none();
    }
}
