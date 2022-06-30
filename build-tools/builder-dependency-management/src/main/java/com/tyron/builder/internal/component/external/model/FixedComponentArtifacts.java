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

package com.tyron.builder.internal.component.external.model;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactSet;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.DefaultArtifactSet;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvableArtifact;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec;

import com.tyron.builder.api.artifacts.component.ComponentArtifactIdentifier;

import com.tyron.builder.api.internal.artifacts.type.ArtifactTypeRegistry;
import com.tyron.builder.api.internal.attributes.ImmutableAttributes;
import com.tyron.builder.internal.component.model.ComponentArtifactMetadata;
import com.tyron.builder.internal.component.model.ComponentArtifacts;
import com.tyron.builder.internal.component.model.ComponentResolveMetadata;
import com.tyron.builder.internal.component.model.ConfigurationMetadata;
import com.tyron.builder.internal.model.CalculatedValueContainerFactory;
import com.tyron.builder.internal.resolve.resolver.ArtifactResolver;

import java.util.List;
import java.util.Map;

/**
 * Uses a fixed set of artifacts for all configurations.
 */
public class FixedComponentArtifacts implements ComponentArtifacts {
    private final ImmutableList<ComponentArtifactMetadata> artifacts;

    public FixedComponentArtifacts(Iterable<? extends ComponentArtifactMetadata> artifacts) {
        this.artifacts = ImmutableList.copyOf(artifacts);
    }

    public List<ComponentArtifactMetadata> getArtifacts() {
        return artifacts;
    }

    @Override
    public ArtifactSet getArtifactsFor(ComponentResolveMetadata component, ConfigurationMetadata configuration, ArtifactResolver artifactResolver, Map<ComponentArtifactIdentifier, ResolvableArtifact> allResolvedArtifacts, ArtifactTypeRegistry artifactTypeRegistry, ExcludeSpec exclusions, ImmutableAttributes overriddenAttributes, CalculatedValueContainerFactory calculatedValueContainerFactory) {
        return DefaultArtifactSet.createForConfiguration(component.getId(), component.getModuleVersionId(), configuration, artifacts, component.getSources(), exclusions, component.getAttributesSchema(), artifactResolver, allResolvedArtifacts, artifactTypeRegistry, overriddenAttributes, calculatedValueContainerFactory);
    }
}
