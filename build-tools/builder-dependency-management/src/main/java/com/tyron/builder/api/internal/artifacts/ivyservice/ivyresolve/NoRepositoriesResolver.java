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

package com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve;

import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactSet;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec;
import com.tyron.builder.internal.resolve.ModuleVersionNotFoundException;

import com.tyron.builder.api.artifacts.component.ComponentIdentifier;

import com.tyron.builder.api.internal.artifacts.type.ArtifactTypeRegistry;
import com.tyron.builder.api.internal.attributes.ImmutableAttributes;
import com.tyron.builder.api.internal.component.ArtifactType;
import com.tyron.builder.internal.component.model.ComponentArtifactMetadata;
import com.tyron.builder.internal.component.model.ComponentOverrideMetadata;
import com.tyron.builder.internal.component.model.ComponentResolveMetadata;
import com.tyron.builder.internal.component.model.ConfigurationMetadata;
import com.tyron.builder.internal.component.model.DependencyMetadata;
import com.tyron.builder.internal.component.model.ModuleSources;
import com.tyron.builder.internal.resolve.resolver.ArtifactResolver;
import com.tyron.builder.internal.resolve.resolver.ComponentMetaDataResolver;
import com.tyron.builder.internal.resolve.resolver.DependencyToComponentIdResolver;
import com.tyron.builder.internal.resolve.resolver.OriginArtifactSelector;
import com.tyron.builder.internal.resolve.result.BuildableArtifactResolveResult;
import com.tyron.builder.internal.resolve.result.BuildableArtifactSetResolveResult;
import com.tyron.builder.internal.resolve.result.BuildableComponentIdResolveResult;
import com.tyron.builder.internal.resolve.result.BuildableComponentResolveResult;

import javax.annotation.Nullable;

/**
 * Used as a fallback when no repositories are defined for a given resolution.
 */
public class NoRepositoriesResolver implements ComponentResolvers, DependencyToComponentIdResolver, ComponentMetaDataResolver, ArtifactResolver, OriginArtifactSelector {
    @Override
    public DependencyToComponentIdResolver getComponentIdResolver() {
        return this;
    }

    @Override
    public ComponentMetaDataResolver getComponentResolver() {
        return this;
    }

    @Override
    public ArtifactResolver getArtifactResolver() {
        return this;
    }

    @Override
    public OriginArtifactSelector getArtifactSelector() {
        return this;
    }

    @Override
    public void resolve(DependencyMetadata dependency, VersionSelector acceptor, @Nullable VersionSelector rejector, BuildableComponentIdResolveResult result) {
        result.failed(new ModuleVersionNotFoundException(dependency.getSelector(), () -> String.format("Cannot resolve external dependency %s because no repositories are defined.", dependency.getSelector())));
    }

    @Override
    public void resolve(ComponentIdentifier identifier, ComponentOverrideMetadata componentOverrideMetadata, BuildableComponentResolveResult result) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isFetchingMetadataCheap(ComponentIdentifier identifier) {
        return true;
    }

    @Nullable
    @Override
    public ArtifactSet resolveArtifacts(ComponentResolveMetadata component, ConfigurationMetadata configuration, ArtifactTypeRegistry artifactTypeRegistry, ExcludeSpec exclusions, ImmutableAttributes overriddenAttributes) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void resolveArtifactsWithType(ComponentResolveMetadata component, ArtifactType artifactType, BuildableArtifactSetResolveResult result) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void resolveArtifact(ComponentArtifactMetadata artifact, ModuleSources moduleSources, BuildableArtifactResolveResult result) {
        throw new UnsupportedOperationException();
    }
}
