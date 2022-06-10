package org.gradle.api.internal.component;

import org.gradle.api.component.Artifact;

public interface ComponentTypeRegistration {
    ArtifactType getArtifactType(Class<? extends Artifact> artifact);

    ComponentTypeRegistration registerArtifactType(Class<? extends Artifact> artifact, ArtifactType artifactType);
}
