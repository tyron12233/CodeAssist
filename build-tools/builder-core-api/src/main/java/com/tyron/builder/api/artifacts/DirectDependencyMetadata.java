package com.tyron.builder.api.artifacts;

import java.util.List;

/**
 * Describes a dependency declared in a resolved component's metadata, which typically originates from
 * a component descriptor (Gradle metadata file, Ivy file, Maven POM). This interface can be used to adjust
 * a dependency's properties via metadata rules (see {@link com.tyron.builder.api.artifacts.dsl.ComponentMetadataHandler}.
 *
 * @since 4.5
 */
public interface DirectDependencyMetadata extends DependencyMetadata<DirectDependencyMetadata> {

    /**
     * Endorse version constraints with {@link VersionConstraint#getStrictVersion()} strict versions} from the target module.
     *
     * Endorsing strict versions of another module/platform means that all strict versions will be interpreted during dependency
     * resolution as if they where defined by the endorsing module itself.
     *
     * @since 6.0
     */
    void endorseStrictVersions();

    /**
     * Resets the {@link #isEndorsingStrictVersions()} state of this dependency.
     *
     * @since 6.0
     */
    void doNotEndorseStrictVersions();

    /**
     * Are the {@link VersionConstraint#getStrictVersion()} strict version} dependency constraints of the target module endorsed?
     *
     * @since 6.0
     */
    boolean isEndorsingStrictVersions();

    /**
     * Returns additional artifact information associated with the dependency that is used to select artifacts in the targeted module.
     * For example, a classifier or type defined in POM metadata or a complete artifact name defined in Ivy metadata.
     *
     * @since 6.3
     */
    List<DependencyArtifact> getArtifactSelectors();

}
