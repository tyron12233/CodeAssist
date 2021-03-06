/*
 * Copyright 2015 the original author or authors.
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

package com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.graph;

import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec;

import com.tyron.builder.api.artifacts.Dependency;
import com.tyron.builder.api.internal.attributes.ImmutableAttributes;
import com.tyron.builder.internal.component.model.ComponentArtifactMetadata;
import com.tyron.builder.internal.component.model.ConfigurationMetadata;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A {@link ResolvedGraphDependency} that is used during the resolution of the dependency graph.
 * Additional fields in this interface are not required to reconstitute the serialized graph, but are using during construction of the graph.
 */
public interface DependencyGraphEdge extends ResolvedGraphDependency {
    DependencyGraphNode getFrom();

    DependencyGraphSelector getSelector();

    ExcludeSpec getExclusions();

    boolean contributesArtifacts();

    List<ComponentArtifactMetadata> getArtifacts(ConfigurationMetadata targetConfiguration);

    ImmutableAttributes getAttributes();

    /**
     * The original dependency instance declared in the build script, if any.
     */
    @Nullable
    Dependency getOriginalDependency();

    boolean isTargetVirtualPlatform();

}
