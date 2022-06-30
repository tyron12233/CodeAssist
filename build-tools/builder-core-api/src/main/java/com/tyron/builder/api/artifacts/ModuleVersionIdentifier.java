package com.tyron.builder.api.artifacts;

import java.io.Serializable;

/**
 * The identifier of a module version.
 */
public interface ModuleVersionIdentifier extends Serializable {

    /**
     * The version of the module
     *
     * @return module version
     */
    String getVersion();

    /**
     * The group of the module.
     *
     * @return module group
     */
    String getGroup();

    /**
     * The name of the module.
     *
     * @return module name
     */
    String getName();

    /**
     * Returns the {@link ModuleIdentifier} containing the group and the name of this module.
     * Contains the same information as {@link #getGroup()} and {@link #getVersion()}
     *
     * @return the module identifier
     * @since 1.4
     */
    ModuleIdentifier getModule();
}