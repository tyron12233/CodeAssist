package com.tyron.builder.composite.internal;

import com.tyron.builder.api.artifacts.component.ProjectComponentIdentifier;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.artifacts.ivyservice.projectmodule.LocalComponentRegistry;
import com.tyron.builder.internal.build.CompositeBuildParticipantBuildState;
import com.tyron.builder.internal.component.local.model.DefaultLocalComponentMetadata;
import com.tyron.builder.internal.component.local.model.LocalComponentMetadata;
import com.tyron.builder.internal.component.local.model.LocalConfigurationMetadata;

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
