package com.tyron.builder.api.artifacts.component;

import com.tyron.builder.api.artifacts.ModuleIdentifier;
import com.tyron.builder.api.artifacts.VersionConstraint;
import com.tyron.builder.internal.scan.UsedByScanPlugin;

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
