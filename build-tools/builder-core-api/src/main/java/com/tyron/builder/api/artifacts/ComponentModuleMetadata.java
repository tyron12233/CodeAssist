package com.tyron.builder.api.artifacts;

import javax.annotation.Nullable;

/**
 * Contains immutable component module metadata information.
 *
 * @since 2.2
 */
public interface ComponentModuleMetadata {

    /**
     * The identifier of the module.
     */
    ModuleIdentifier getId();

    /**
     * The identifier of module that replaces this module.
     * A real world example: 'com.google.collections:google-collections' is replaced by 'com.google.guava:guava'.
     */
    @Nullable ModuleIdentifier getReplacedBy();
}
