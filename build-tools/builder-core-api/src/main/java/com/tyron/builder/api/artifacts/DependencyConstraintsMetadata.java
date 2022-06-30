package com.tyron.builder.api.artifacts;

/**
 * Describes the dependency constraints of a variant declared in a resolved component's metadata, which typically originate from
 * a component descriptor (Gradle metadata file). This interface can be used to adjust the dependencies
 * of a published component via metadata rules (see {@link com.tyron.builder.api.artifacts.dsl.ComponentMetadataHandler}.
 *
 * @since 4.5
 */
public interface DependencyConstraintsMetadata extends DependenciesMetadata<DependencyConstraintMetadata> {
}
