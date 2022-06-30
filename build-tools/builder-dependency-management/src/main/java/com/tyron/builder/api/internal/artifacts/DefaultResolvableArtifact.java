/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvableArtifact;
import com.tyron.builder.internal.component.local.model.ComponentFileArtifactIdentifier;
import com.tyron.builder.internal.component.model.DefaultIvyArtifactName;
import com.tyron.builder.internal.component.model.IvyArtifactName;

import com.tyron.builder.api.Task;
import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.api.artifacts.ResolvedArtifact;
import com.tyron.builder.api.artifacts.component.ComponentArtifactIdentifier;
import com.tyron.builder.api.artifacts.component.ProjectComponentIdentifier;
import com.tyron.builder.api.internal.tasks.FinalizeAction;
import com.tyron.builder.api.internal.tasks.TaskDependencyContainer;
import com.tyron.builder.api.internal.tasks.TaskDependencyResolveContext;
import com.tyron.builder.internal.Describables;
import com.tyron.builder.internal.model.CalculatedValue;
import com.tyron.builder.internal.model.CalculatedValueContainerFactory;

import javax.annotation.Nullable;
import java.io.File;

public class DefaultResolvableArtifact implements ResolvableArtifact {
    private final ModuleVersionIdentifier owner;
    private final IvyArtifactName artifact;
    private final ComponentArtifactIdentifier artifactId;
    private final TaskDependencyContainer buildDependencies;
    private final CalculatedValue<File> fileSource;
    private final FinalizeAction resolvedArtifactDependency;
    private final CalculatedValueContainerFactory calculatedValueContainerFactory;
    private final PreResolvedResolvableArtifact publicView;

    public DefaultResolvableArtifact(@Nullable ModuleVersionIdentifier owner, IvyArtifactName artifact, ComponentArtifactIdentifier artifactId, TaskDependencyContainer builtBy, CalculatedValue<File> fileSource, CalculatedValueContainerFactory calculatedValueContainerFactory) {
        this.owner = owner;
        this.artifact = artifact;
        this.artifactId = artifactId;
        this.buildDependencies = builtBy;
        this.fileSource = fileSource;
        this.resolvedArtifactDependency = new FinalizeAction() {
            @Override
            public TaskDependencyContainer getDependencies() {
                return buildDependencies;
            }

            @Override
            public void execute(Task task) {
                // Eagerly calculate the file if this will be used as a dependency of some task
                // This is to avoid having to lock the project when a consuming task in another project runs
                if (isResolveSynchronously()) {
                    fileSource.getResourceToLock().applyToMutableState(o -> fileSource.finalizeIfNotAlready());
                }
            }
        };
        this.calculatedValueContainerFactory = calculatedValueContainerFactory;
        publicView = new PreResolvedResolvableArtifact(owner, artifact, artifactId, fileSource, buildDependencies, calculatedValueContainerFactory);
    }

    @Override
    public void visitDependencies(TaskDependencyResolveContext context) {
        context.add(resolvedArtifactDependency);
    }

    public IvyArtifactName getArtifactName() {
        return artifact;
    }

    @Override
    public ComponentArtifactIdentifier getId() {
        return artifactId;
    }

    @Override
    public String toString() {
        return artifactId.getDisplayName();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null || obj.getClass() != getClass()) {
            return false;
        }
        DefaultResolvableArtifact other = (DefaultResolvableArtifact) obj;
        return other.artifactId.equals(artifactId);
    }

    @Override
    public int hashCode() {
        return artifactId.hashCode();
    }

    @Override
    public ResolvedArtifact toPublicView() {
        return publicView;
    }

    @Override
    public ResolvableArtifact transformedTo(File file) {
        IvyArtifactName artifactName = DefaultIvyArtifactName.forFile(file, artifact.getClassifier());
        ComponentArtifactIdentifier newId = new ComponentFileArtifactIdentifier(artifactId.getComponentIdentifier(), artifactName);
        return new PreResolvedResolvableArtifact(owner, artifactName, newId, calculatedValueContainerFactory.create(Describables.of(newId), file), TaskDependencyContainer.EMPTY, calculatedValueContainerFactory);
    }

    @Override
    public boolean isResolveSynchronously() {
        if (artifactId.getComponentIdentifier() instanceof ProjectComponentIdentifier) {
            // Don't bother resolving local components asynchronously
            return true;
        }
        return fileSource.isFinalized();
    }

    @Override
    public CalculatedValue<File> getFileSource() {
        return fileSource;
    }

    @Override
    public File getFile() {
        fileSource.finalizeIfNotAlready();
        return fileSource.get();
    }
}
