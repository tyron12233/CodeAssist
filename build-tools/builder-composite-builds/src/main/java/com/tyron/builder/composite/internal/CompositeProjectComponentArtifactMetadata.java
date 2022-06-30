package com.tyron.builder.composite.internal;

import com.tyron.builder.api.artifacts.component.ComponentArtifactIdentifier;
import com.tyron.builder.api.artifacts.component.ProjectComponentIdentifier;
import com.tyron.builder.api.tasks.TaskDependency;
import com.tyron.builder.internal.Describables;
import com.tyron.builder.internal.DisplayName;
import com.tyron.builder.internal.component.local.model.LocalComponentArtifactMetadata;
import com.tyron.builder.internal.component.model.IvyArtifactName;

import java.io.File;

public class CompositeProjectComponentArtifactMetadata implements LocalComponentArtifactMetadata, ComponentArtifactIdentifier, DisplayName {
    private final ProjectComponentIdentifier componentIdentifier;
    private final LocalComponentArtifactMetadata delegate;
    private final File file;

    public CompositeProjectComponentArtifactMetadata(ProjectComponentIdentifier componentIdentifier, LocalComponentArtifactMetadata delegate, File file) {
        this.componentIdentifier = componentIdentifier;
        this.delegate = delegate;
        this.file = file;
    }

    public LocalComponentArtifactMetadata getDelegate() {
        return delegate;
    }

    @Override
    public ProjectComponentIdentifier getComponentId() {
        return componentIdentifier;
    }

    @Override
    public ComponentArtifactIdentifier getId() {
        return this;
    }

    @Override
    public IvyArtifactName getName() {
        return delegate.getName();
    }

    @Override
    public ProjectComponentIdentifier getComponentIdentifier() {
        return componentIdentifier;
    }

    @Override
    public String getDisplayName() {
        return delegate.getId().getDisplayName();
    }

    @Override
    public String getCapitalizedDisplayName() {
        return Describables.of(delegate.getId()).getCapitalizedDisplayName();
    }

    @Override
    public File getFile() {
        return file;
    }

    @Override
    public TaskDependency getBuildDependencies() {
        return delegate.getBuildDependencies();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CompositeProjectComponentArtifactMetadata)) {
            return false;
        }

        CompositeProjectComponentArtifactMetadata that = (CompositeProjectComponentArtifactMetadata) o;
        return delegate.equals(that.delegate);
    }

    @Override
    public int hashCode() {
        return delegate.hashCode();
    }
}
