/*
 * Copyright 2016 the original author or authors.
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

import com.tyron.builder.api.artifacts.component.BuildIdentifier;
import com.tyron.builder.api.artifacts.component.ProjectComponentIdentifier;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.project.ProjectStateUnk;
import com.tyron.builder.api.internal.project.ProjectStateRegistry;
import com.tyron.builder.api.internal.tasks.NodeExecutionContext;
import com.tyron.builder.api.internal.tasks.TaskDependencyResolveContext;
import com.tyron.builder.internal.Describables;
import com.tyron.builder.internal.component.local.model.LocalComponentMetadata;
import com.tyron.builder.internal.model.CalculatedValueContainer;
import com.tyron.builder.internal.model.CalculatedValueContainerFactory;
import com.tyron.builder.internal.model.ValueCalculator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultLocalComponentRegistry implements LocalComponentRegistry {
    private final BuildIdentifier thisBuild;
    private final ProjectStateRegistry projectStateRegistry;
    private final CalculatedValueContainerFactory calculatedValueContainerFactory;
    private final LocalComponentProvider provider;
    private final LocalComponentInAnotherBuildProvider otherBuildProvider;
    private final Map<ProjectComponentIdentifier, CalculatedValueContainer<LocalComponentMetadata, ?>> projects = new ConcurrentHashMap<>();

    public DefaultLocalComponentRegistry(
        BuildIdentifier thisBuild,
        ProjectStateRegistry projectStateRegistry,
        CalculatedValueContainerFactory calculatedValueContainerFactory,
        LocalComponentProvider provider,
        LocalComponentInAnotherBuildProvider otherBuildProvider
    ) {
        this.thisBuild = thisBuild;
        this.projectStateRegistry = projectStateRegistry;
        this.calculatedValueContainerFactory = calculatedValueContainerFactory;
        this.provider = provider;
        this.otherBuildProvider = otherBuildProvider;
    }

    @Override
    public LocalComponentMetadata getComponent(ProjectComponentIdentifier projectIdentifier) {
        CalculatedValueContainer<LocalComponentMetadata, ?> valueContainer = projects.computeIfAbsent(projectIdentifier, projectComponentIdentifier -> {
            ProjectStateUnk projectState = projectStateRegistry.stateFor(projectIdentifier);
            return calculatedValueContainerFactory.create(Describables.of("metadata of", projectIdentifier), new MetadataSupplier(projectState));
        });
        // Calculate the value after adding the entry to the map, so that the value container can take care of thread synchronization
        valueContainer.finalizeIfNotAlready();
        return valueContainer.get();
    }

    private class MetadataSupplier implements ValueCalculator<LocalComponentMetadata> {
        private final ProjectStateUnk projectState;

        public MetadataSupplier(ProjectStateUnk projectState) {
            this.projectState = projectState;
        }

        @Override
        public void visitDependencies(TaskDependencyResolveContext context) {
        }

        @Override
        public boolean usesMutableProjectState() {
            return false;
        }

        @Override
        public ProjectInternal getOwningProject() {
            return null;
        }

        @Override
        public LocalComponentMetadata calculateValue(NodeExecutionContext context) {
            if (isLocalProject(projectState.getComponentIdentifier())) {
                return provider.getComponent(projectState);
            } else {
                return otherBuildProvider.getComponent(projectState);
            }
        }

        private boolean isLocalProject(ProjectComponentIdentifier projectIdentifier) {
            return projectIdentifier.getBuild().equals(thisBuild);
        }
    }
}
