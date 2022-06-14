/*
 * Copyright 2013 the original author or authors.
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

package com.tyron.builder.internal.component.external.model;

import com.tyron.builder.api.artifacts.ArtifactIdentifier;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.artifacts.component.ModuleComponentIdentifier;
import com.tyron.builder.api.internal.artifacts.DefaultArtifactIdentifier;
import com.tyron.builder.api.internal.tasks.TaskDependencyInternal;
import com.tyron.builder.api.tasks.TaskDependency;
import com.tyron.builder.internal.component.model.IvyArtifactName;

public class DefaultModuleComponentArtifactMetadata implements ModuleComponentArtifactMetadata {
    private final DefaultModuleComponentArtifactIdentifier id;

    public DefaultModuleComponentArtifactMetadata(ModuleComponentIdentifier componentIdentifier, IvyArtifactName artifact) {
        this(new DefaultModuleComponentArtifactIdentifier(componentIdentifier, artifact));
    }

    public DefaultModuleComponentArtifactMetadata(ModuleComponentArtifactIdentifier moduleComponentArtifactIdentifier) {
        this.id = (DefaultModuleComponentArtifactIdentifier) moduleComponentArtifactIdentifier;
    }

    @Override
    public String toString() {
        return id.toString();
    }

    @Override
    public ModuleComponentArtifactIdentifier getId() {
        return id;
    }

    @Override
    public ComponentIdentifier getComponentId() {
        return id.getComponentIdentifier();
    }

    @Override
    public ArtifactIdentifier toArtifactIdentifier() {
        return new DefaultArtifactIdentifier(id);
    }

    @Override
    public IvyArtifactName getName() {
        return id.getName();
    }

    @Override
    public TaskDependency getBuildDependencies() {
        return TaskDependencyInternal.EMPTY;
    }
}
