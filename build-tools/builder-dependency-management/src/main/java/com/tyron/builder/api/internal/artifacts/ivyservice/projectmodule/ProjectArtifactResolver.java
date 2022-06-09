/*
 * Copyright 2020 the original author or authors.
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

package com.tyron.builder.api.internal.artifacts.ivyservice.projectmodule;

import com.tyron.builder.api.artifacts.component.ProjectComponentIdentifier;
import com.tyron.builder.api.internal.component.ArtifactType;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.project.ProjectStateUnk;
import com.tyron.builder.api.internal.project.ProjectStateRegistry;
import com.tyron.builder.api.internal.tasks.NodeExecutionContext;
import com.tyron.builder.api.internal.tasks.TaskDependencyResolveContext;
import com.tyron.builder.internal.component.local.model.LocalComponentArtifactMetadata;
import com.tyron.builder.internal.component.model.ComponentArtifactMetadata;
import com.tyron.builder.internal.component.model.ComponentResolveMetadata;
import com.tyron.builder.internal.component.model.ModuleSources;
import com.tyron.builder.internal.model.ValueCalculator;
import com.tyron.builder.internal.resolve.resolver.ArtifactResolver;
import com.tyron.builder.internal.resolve.result.BuildableArtifactResolveResult;
import com.tyron.builder.internal.resolve.result.BuildableArtifactSetResolveResult;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

import java.io.File;

@ServiceScope(Scopes.Build.class)
public class ProjectArtifactResolver implements ArtifactResolver {
    private final ProjectStateRegistry projectStateRegistry;

    public ProjectArtifactResolver(ProjectStateRegistry projectStateRegistry) {
        this.projectStateRegistry = projectStateRegistry;
    }

    @Override
    public void resolveArtifactsWithType(ComponentResolveMetadata component, ArtifactType artifactType, BuildableArtifactSetResolveResult result) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void resolveArtifact(ComponentArtifactMetadata artifact, ModuleSources moduleSources, BuildableArtifactResolveResult result) {
        LocalComponentArtifactMetadata projectArtifact = (LocalComponentArtifactMetadata) artifact;
        ProjectComponentIdentifier projectId = (ProjectComponentIdentifier) artifact.getComponentId();
        File localArtifactFile = projectStateRegistry.stateFor(projectId).fromMutableState(p -> projectArtifact.getFile());
        if (localArtifactFile != null) {
            result.resolved(localArtifactFile);
        } else {
            result.notFound(projectArtifact.getId());
        }
    }

    public ValueCalculator<File> resolveArtifactLater(ComponentArtifactMetadata artifact) {
        LocalComponentArtifactMetadata projectArtifact = (LocalComponentArtifactMetadata) artifact;
        ProjectComponentIdentifier projectId = (ProjectComponentIdentifier) artifact.getComponentId();
        ProjectStateUnk projectState = projectStateRegistry.stateFor(projectId);
        return new ResolvingCalculator(projectState, projectArtifact);
    }

    private static class ResolvingCalculator implements ValueCalculator<File> {
        private final ProjectStateUnk projectState;
        private final LocalComponentArtifactMetadata projectArtifact;

        public ResolvingCalculator(ProjectStateUnk projectState, LocalComponentArtifactMetadata projectArtifact) {
            this.projectState = projectState;
            this.projectArtifact = projectArtifact;
        }

        @Override
        public boolean usesMutableProjectState() {
            return true;
        }

        @Override
        public ProjectInternal getOwningProject() {
            return projectState.getMutableModel();
        }

        @Override
        public void visitDependencies(TaskDependencyResolveContext context) {
        }

        @Override
        public File calculateValue(NodeExecutionContext context) {
            return projectState.fromMutableState(p -> projectArtifact.getFile());
        }
    }
}
