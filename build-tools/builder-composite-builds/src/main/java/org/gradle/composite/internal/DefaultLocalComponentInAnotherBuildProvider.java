package org.gradle.composite.internal;

import org.gradle.api.internal.artifacts.ivyservice.projectmodule.LocalComponentInAnotherBuildProvider;
import org.gradle.api.internal.project.ProjectState;
import org.gradle.internal.build.CompositeBuildParticipantBuildState;
import org.gradle.internal.build.IncludedBuildState;
import org.gradle.internal.component.local.model.LocalComponentMetadata;

/**
 * Provides the metadata for a local component consumed from a build that is not the producing build.
 *
 * Currently, the metadata for a component is different based on whether it is consumed from the producing build or from another build. This difference should go away, but in the meantime this class provides the mapping.
 */
public class DefaultLocalComponentInAnotherBuildProvider implements LocalComponentInAnotherBuildProvider {
    private final IncludedBuildDependencyMetadataBuilder dependencyMetadataBuilder;

    public DefaultLocalComponentInAnotherBuildProvider(IncludedBuildDependencyMetadataBuilder dependencyMetadataBuilder) {
        this.dependencyMetadataBuilder = dependencyMetadataBuilder;
    }

    public LocalComponentMetadata getComponent(ProjectState projectState) {
        // TODO - this should work for any build, rather than just an included build
        CompositeBuildParticipantBuildState buildState = (CompositeBuildParticipantBuildState) projectState.getOwner();
        if (buildState instanceof IncludedBuildState) {
            // make sure the build is configured now (not do this for the root build, as we are already configuring it right now)
            buildState.ensureProjectsConfigured();
        }
        // Metadata builder uses mutable project state, so synchronize access to the project state
        return projectState.fromMutableState(p -> dependencyMetadataBuilder.build(buildState, projectState.getComponentIdentifier()));
    }
}
