package org.gradle.api.artifacts.component;

import org.gradle.api.artifacts.ModuleIdentifier;
import org.gradle.api.artifacts.VersionConstraint;
import org.gradle.internal.scan.UsedByScanPlugin;

/**
 * Criteria for selecting a component instance that is available as a module version.
 *
 * @since 1.10
 */
@UsedByScanPlugin
public interface ModuleComponentSelector extends ComponentSelector {
    /**
     * The group of the module to select the component from.
     *
     * @return Module group
     * @since 1.10
     */
    String getGroup();

    /**
     * The name of the module to select the component from.
     *
     * @return Module name
     */
    String getModule();

    /**
     * The version of the module to select the component from.
     *
     * @return Module version
     */
    String getVersion();

    /**
     * The version constraint of the module to select the component from.
     *
     * @return Module version constraint
     *
     * @since 4.4
     */
    VersionConstraint getVersionConstraint();

    /**
     * The module identifier of the component. Returns the same information
     * as {@link #getGroup()} and {@link #getModule()}.
     *
     * @return the module identifier
     *
     * @since 4.9
     */
    ModuleIdentifier getModuleIdentifier();
}
