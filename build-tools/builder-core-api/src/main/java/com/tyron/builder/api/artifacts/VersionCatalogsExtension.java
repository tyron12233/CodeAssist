package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.InvalidUserDataException;

import java.util.Optional;
import java.util.Set;

/**
 * Gives access to all version catalogs available.
 *
 * @since 7.0
 */
@Incubating
public interface VersionCatalogsExtension extends Iterable<VersionCatalog> {

    /**
     * Tries to find a catalog with the corresponding name
     * @param name the name of the catalog
     */
    Optional<VersionCatalog> find(String name);

    /**
     * Returns the catalog with the supplied name or throws an exception
     * if it doesn't exist.
     */
    default VersionCatalog named(String name) {
        return find(name).orElseThrow(() -> new InvalidUserDataException("Catalog named " + name + " doesn't exist"));
    }

    /**
     * Returns the list of catalog names
     *
     * @since 7.2
     */
    Set<String> getCatalogNames();
}
