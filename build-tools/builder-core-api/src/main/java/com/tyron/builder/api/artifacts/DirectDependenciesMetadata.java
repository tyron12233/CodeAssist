package com.tyron.builder.api.artifacts;

/**
 * Describes the dependencies of a variant declared in a resolved component's metadata, which typically originate from
 * a component descriptor (Gradle metadata file, Ivy file, Maven POM). This interface can be used to adjust the dependencies
 * of a published component via metadata rules (see {@link com.tyron.builder.api.artifacts.dsl.ComponentMetadataHandler}.
 *
 * @since 4.5
 */
public interface DirectDependenciesMetadata extends DependenciesMetadata<DirectDependencyMetadata> {

}
