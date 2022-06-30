/*
 * Copyright 2014 the original author or authors.
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

package com.tyron.builder.internal.component.model;

import com.tyron.builder.api.artifacts.component.ComponentArtifactIdentifier;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.tasks.TaskDependency;

/**
 * Meta-data for an artifact that belongs to some component.
 */
public interface ComponentArtifactMetadata {
    /**
     * Returns the identifier for this artifact.
     */
    ComponentArtifactIdentifier getId();

    /**
     * Returns the identifier for the component that this artifact belongs to.
     */
    ComponentIdentifier getComponentId();

    /**
     * Returns this artifact as an Ivy artifact. This method is here to allow the artifact to be exposed in a backward-compatible way.
     */
    IvyArtifactName getName();

    /**
     * Collects the build dependencies of this artifact, which are required to build this artifact
     */
    TaskDependency getBuildDependencies();
}
