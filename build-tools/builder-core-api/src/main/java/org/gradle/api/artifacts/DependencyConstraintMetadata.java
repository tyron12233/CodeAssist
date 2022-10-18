package org.gradle.api.artifacts;

/**
 * Describes a dependency constraint declared in a resolved component's metadata, which typically originates from
 * a component descriptor (Gradle metadata file). This interface can be used to adjust
 * a dependency constraint's properties via metadata rules (see {@link org.gradle.api.artifacts.dsl.ComponentMetadataHandler}.
 *
 * @since 4.5
 */
public interface DependencyConstraintMetadata extends DependencyMetadata<DependencyConstraintMetadata> {

}
