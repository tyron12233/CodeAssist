/*
 * Copyright 2017 the original author or authors.
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

package com.tyron.builder.internal.resolve.resolver;

import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactSet;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec;
import com.tyron.builder.internal.component.local.model.LocalFileDependencyMetadata;
import com.tyron.builder.internal.component.model.ComponentArtifactMetadata;
import com.tyron.builder.internal.component.model.ComponentResolveMetadata;
import com.tyron.builder.internal.component.model.ConfigurationMetadata;

import com.tyron.builder.api.internal.attributes.ImmutableAttributes;

import java.util.Collection;

public interface ArtifactSelector {
    /**
     * Creates a set that will resolve the artifacts of the given configuration, minus those artifacts that are excluded.
     */
    ArtifactSet resolveArtifacts(ComponentResolveMetadata component, ConfigurationMetadata configuration, ExcludeSpec exclusions, ImmutableAttributes overriddenAttributes);

    /**
     * Creates a set that will resolve the given artifacts of the given component.
     */
    ArtifactSet resolveArtifacts(ComponentResolveMetadata component, Collection<? extends ComponentArtifactMetadata> artifacts, ImmutableAttributes overriddenAttributes);

    /**
     * Creates a set that will resolve the artifacts of the file dependency.
     */
    ArtifactSet resolveArtifacts(LocalFileDependencyMetadata fileDependencyMetadata);
}
