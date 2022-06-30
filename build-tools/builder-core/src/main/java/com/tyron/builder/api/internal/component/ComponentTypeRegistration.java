package com.tyron.builder.api.internal.component;

import com.tyron.builder.api.component.Artifact;

public interface ComponentTypeRegistration {
    ArtifactType getArtifactType(Class<? extends Artifact> artifact);

    ComponentTypeRegistration registerArtifactType(Class<? extends Artifact> artifact, ArtifactType artifactType);
}
