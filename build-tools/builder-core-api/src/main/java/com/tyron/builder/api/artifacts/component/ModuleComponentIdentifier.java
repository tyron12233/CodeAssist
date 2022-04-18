package com.tyron.builder.api.artifacts.component;

import com.tyron.builder.api.artifacts.ModuleIdentifier;

/**
 * An identifier for a component instance which is available as a module version.
 *
 * @since 1.10
 */
//@UsedByScanPlugin
public interface ModuleComponentIdentifier extends ComponentIdentifier {
    /**
     * The module group of the component.
     *
     * @return Component group
     * @since 1.10
     */
    String getGroup();

    /**
     * The module name of the component.
     *
     * @return Component module
     * @since 1.10
     */
    String getModule();

    /**
     * The module version of the component.
     *
     * @return Component version
     * @since 1.10
     */
    String getVersion();

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