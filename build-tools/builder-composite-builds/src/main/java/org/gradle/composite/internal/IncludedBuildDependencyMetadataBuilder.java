package org.gradle.composite.internal;

import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.LocalComponentRegistry;
import org.gradle.internal.build.CompositeBuildParticipantBuildState;
import org.gradle.internal.component.local.model.DefaultLocalComponentMetadata;
import org.gradle.internal.component.local.model.LocalComponentMetadata;
import org.gradle.internal.component.local.model.LocalConfigurationMetadata;

import java.io.File;

import javax.annotation.Nullable;

public class IncludedBuildDependencyMetadataBuilder {

    public LocalComponentMetadata build(CompositeBuildParticipantBuildState build, ProjectComponentIdentifier projectIdentifier) {
        GradleInternal gradle = build.getMutableModel();
        LocalComponentRegistry localComponentRegistry = gradle.getServices().get(LocalComponentRegistry.class);
        DefaultLocalComponentMetadata originalComponent = (DefaultLocalComponentMetadata) localComponentRegistry.getComponent(projectIdentifier);

        ProjectComponentIdentifier foreignIdentifier = build.idToReferenceProjectFromAnotherBuild(projectIdentifier);
        return createCompositeCopy(foreignIdentifier, originalComponent);
    }

    private LocalComponentMetadata createCompositeCopy(final ProjectComponentIdentifier componentIdentifier, DefaultLocalComponentMetadata originalComponentMetadata) {
        return originalComponentMetadata.copy(componentIdentifier, originalArtifact -> {
            // Currently need to resolve the file, so that the artifact can be used in both a script classpath and the main build. Instead, this should be resolved as required
            File file = originalArtifact.getFile();
            return new CompositeProjectComponentArtifactMetadata(componentIdentifier, originalArtifact, file);
        });
    }
}
