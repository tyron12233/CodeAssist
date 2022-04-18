package com.tyron.builder.api.artifacts;

import java.io.Serializable;

/**
 * The identifier of a module.
 */
public interface ModuleIdentifier extends Serializable {

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
}