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

import com.google.common.collect.Maps;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactSet;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.DefaultArtifactSet;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.FileDependencyArtifactSet;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvableArtifact;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.excludes.simple.DefaultExcludeFactory;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec;
import com.tyron.builder.api.internal.artifacts.type.ArtifactTypeRegistry;
import com.tyron.builder.internal.component.local.model.LocalFileDependencyMetadata;
import com.tyron.builder.internal.component.model.ComponentArtifactMetadata;
import com.tyron.builder.internal.component.model.ComponentResolveMetadata;
import com.tyron.builder.internal.component.model.ConfigurationMetadata;

import com.tyron.builder.api.artifacts.component.ComponentArtifactIdentifier;
import com.tyron.builder.api.internal.attributes.ImmutableAttributes;
import com.tyron.builder.internal.model.CalculatedValueContainerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public class DefaultArtifactSelector implements ArtifactSelector {
    private static final ExcludeSpec EXCLUDE_NONE = new DefaultExcludeFactory().nothing();

    private final Map<ComponentArtifactIdentifier, ResolvableArtifact> allResolvedArtifacts = Maps.newHashMap();
    private final List<OriginArtifactSelector> selectors;
    private final ArtifactTypeRegistry artifactTypeRegistry;
    private final ArtifactResolver artifactResolver;
    private final CalculatedValueContainerFactory calculatedValueContainerFactory;

    public DefaultArtifactSelector(List<OriginArtifactSelector> selectors, ArtifactResolver artifactResolver, ArtifactTypeRegistry artifactTypeRegistry, CalculatedValueContainerFactory calculatedValueContainerFactory) {
        this.selectors = selectors;
        this.artifactTypeRegistry = artifactTypeRegistry;
        this.artifactResolver = artifactResolver;
        this.calculatedValueContainerFactory = calculatedValueContainerFactory;
    }

    @Override
    public ArtifactSet resolveArtifacts(LocalFileDependencyMetadata fileDependencyMetadata) {
        return new FileDependencyArtifactSet(fileDependencyMetadata, artifactTypeRegistry, calculatedValueContainerFactory);
    }

    @Override
    public ArtifactSet resolveArtifacts(ComponentResolveMetadata component, ConfigurationMetadata configuration, ExcludeSpec exclusions, ImmutableAttributes overriddenAttributes) {
        ArtifactSet artifacts = null;
        for (OriginArtifactSelector selector : selectors) {
            artifacts = selector.resolveArtifacts(component, configuration, artifactTypeRegistry, exclusions, overriddenAttributes);
            if (artifacts != null) {
                break;
            }
        }
        if (artifacts == null) {
            throw new IllegalStateException("No artifacts selected.");
        }
        return artifacts;
    }

    @Override
    public ArtifactSet resolveArtifacts(ComponentResolveMetadata component, Collection<? extends ComponentArtifactMetadata> artifacts, ImmutableAttributes overriddenAttributes) {
        return DefaultArtifactSet
                .adHocVariant(component.getId(), component.getModuleVersionId(), artifacts, component.getSources(), EXCLUDE_NONE, component.getAttributesSchema(), artifactResolver, allResolvedArtifacts, artifactTypeRegistry, component.getAttributes(), overriddenAttributes, calculatedValueContainerFactory);
    }
}
