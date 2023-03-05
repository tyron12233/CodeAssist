package org.gradle.plugins.ide.idea.model.internal;

import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.plugins.ide.idea.internal.IdeaModuleMetadata;
import org.gradle.plugins.ide.idea.model.ModuleDependency;
import org.gradle.plugins.ide.internal.IdeArtifactRegistry;

class ModuleDependencyBuilder {
    private final IdeArtifactRegistry ideArtifactRegistry;

    public ModuleDependencyBuilder(IdeArtifactRegistry ideArtifactRegistry) {
        this.ideArtifactRegistry = ideArtifactRegistry;
    }

    public ModuleDependency create(ProjectComponentIdentifier id, String scope) {
        return new ModuleDependency(determineProjectName(id), scope);
    }

    private String determineProjectName(ProjectComponentIdentifier id) {
        IdeaModuleMetadata moduleMetadata = ideArtifactRegistry.getIdeProject(IdeaModuleMetadata.class, id);
        return moduleMetadata == null ? id.getProjectName() : moduleMetadata.getName();
    }
}
