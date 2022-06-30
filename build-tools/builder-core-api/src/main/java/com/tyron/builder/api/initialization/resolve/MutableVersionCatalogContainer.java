package com.tyron.builder.api.initialization.resolve;

import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.NamedDomainObjectContainer;
import com.tyron.builder.api.initialization.dsl.VersionCatalogBuilder;

/**
 * The container for declaring version catalogs
 *
 * @since 7.0
 */
@Incubating
public interface MutableVersionCatalogContainer extends NamedDomainObjectContainer<VersionCatalogBuilder> {
}
