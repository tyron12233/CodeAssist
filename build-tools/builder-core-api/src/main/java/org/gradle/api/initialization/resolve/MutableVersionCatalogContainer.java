package org.gradle.api.initialization.resolve;

import org.gradle.api.Incubating;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.initialization.dsl.VersionCatalogBuilder;

/**
 * The container for declaring version catalogs
 *
 * @since 7.0
 */
@Incubating
public interface MutableVersionCatalogContainer extends NamedDomainObjectContainer<VersionCatalogBuilder> {
}
