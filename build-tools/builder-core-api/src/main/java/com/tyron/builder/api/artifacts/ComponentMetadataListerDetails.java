package com.tyron.builder.api.artifacts;

import java.util.List;

/**
 * Allows a custom version lister to specify the list of versions known
 * for a specific module.
 *
 * @since 4.9
 */
public interface ComponentMetadataListerDetails {
    /**
     * Gives access to the module identifier for which the version lister should
     * return the list of versions.
     * @return the module identifier for which versions are requested
     *
     * @since 4.9
     */
    ModuleIdentifier getModuleIdentifier();

    /**
     * List the versions of the requested component.
     * @param versions the list of versions for the requested component.
     *
     * @since 4.9
     */
    void listed(List<String> versions);
}
