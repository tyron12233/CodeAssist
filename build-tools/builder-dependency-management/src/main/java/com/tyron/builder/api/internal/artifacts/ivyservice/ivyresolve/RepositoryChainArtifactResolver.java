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

import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactSet;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec;
import com.tyron.builder.api.internal.artifacts.repositories.metadata.MavenImmutableAttributesFactory;

import com.tyron.builder.api.attributes.Category;

import com.tyron.builder.api.internal.artifacts.type.ArtifactTypeRegistry;
import com.tyron.builder.api.internal.attributes.AttributeValue;
import com.tyron.builder.api.internal.attributes.ImmutableAttributes;
import com.tyron.builder.api.internal.component.ArtifactType;
import com.tyron.builder.internal.component.model.ComponentArtifactMetadata;
import com.tyron.builder.internal.component.model.ComponentResolveMetadata;
import com.tyron.builder.internal.component.model.ConfigurationMetadata;
import com.tyron.builder.internal.component.model.ModuleSources;
import com.tyron.builder.internal.model.CalculatedValueContainerFactory;
import com.tyron.builder.internal.resolve.resolver.ArtifactResolver;
import com.tyron.builder.internal.resolve.resolver.OriginArtifactSelector;
import com.tyron.builder.internal.resolve.result.BuildableArtifactResolveResult;
import com.tyron.builder.internal.resolve.result.BuildableArtifactSetResolveResult;
import com.tyron.builder.internal.resolve.result.DefaultBuildableComponentArtifactsResolveResult;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

class RepositoryChainArtifactResolver implements ArtifactResolver, OriginArtifactSelector {
    private final Map<String, ModuleComponentRepository> repositories = new LinkedHashMap<>();
    private final CalculatedValueContainerFactory calculatedValueContainerFactory;

    public RepositoryChainArtifactResolver(CalculatedValueContainerFactory calculatedValueContainerFactory) {
        this.calculatedValueContainerFactory = calculatedValueContainerFactory;
    }

    void add(ModuleComponentRepository repository) {
        repositories.put(repository.getId(), repository);
    }

    @Override
    public void resolveArtifactsWithType(ComponentResolveMetadata component, ArtifactType artifactType, BuildableArtifactSetResolveResult result) {
        ModuleComponentRepository sourceRepository = findSourceRepository(component.getSources());
        // First try to determine the artifacts locally before going remote
        sourceRepository.getLocalAccess().resolveArtifactsWithType(component, artifactType, result);
        if (!result.hasResult()) {
            sourceRepository.getRemoteAccess().resolveArtifactsWithType(component, artifactType, result);
        }
    }

    @Nullable
    @Override
    public ArtifactSet resolveArtifacts(ComponentResolveMetadata component, ConfigurationMetadata configuration, ArtifactTypeRegistry artifactTypeRegistry, ExcludeSpec exclusions, ImmutableAttributes overriddenAttributes) {
        if (component.getSources() == null) {
            // virtual components have no source
            return ArtifactSet.NO_ARTIFACTS;
        }
        if (configuration.getArtifacts().isEmpty()) {
            // checks if it's a derived platform
            AttributeValue<String> componentTypeEntry = configuration.getAttributes().findEntry(
                    MavenImmutableAttributesFactory.CATEGORY_ATTRIBUTE);
            if (componentTypeEntry.isPresent()) {
                String value = componentTypeEntry.get();
                if (Category.REGULAR_PLATFORM.equals(value) || Category.ENFORCED_PLATFORM.equals(value)) {
                    return ArtifactSet.NO_ARTIFACTS;
                }
            }
        }
        ModuleComponentRepository sourceRepository = findSourceRepository(component.getSources());
        // First try to determine the artifacts locally before going remote
        DefaultBuildableComponentArtifactsResolveResult result = new DefaultBuildableComponentArtifactsResolveResult();
        sourceRepository.getLocalAccess().resolveArtifacts(component, configuration, result);
        if (!result.hasResult()) {
            sourceRepository.getRemoteAccess().resolveArtifacts(component, configuration, result);
        }
        if (result.hasResult()) {
            return result.getResult().getArtifactsFor(component, configuration, this, sourceRepository.getArtifactCache(), artifactTypeRegistry, exclusions, overriddenAttributes, calculatedValueContainerFactory);
        }
        return null;
    }

    @Override
    public void resolveArtifact(ComponentArtifactMetadata artifact, ModuleSources sources, BuildableArtifactResolveResult result) {
        ModuleComponentRepository sourceRepository = findSourceRepository(sources);

        // First try to resolve the artifacts locally before going remote
        sourceRepository.getLocalAccess().resolveArtifact(artifact, sources, result);
        if (!result.hasResult()) {
            sourceRepository.getRemoteAccess().resolveArtifact(artifact, sources, result);
        }
    }

    private ModuleComponentRepository findSourceRepository(ModuleSources sources) {
        RepositoryChainModuleSource repositoryChainModuleSource = sources.getSource(RepositoryChainModuleSource.class).get();
        ModuleComponentRepository moduleVersionRepository = repositories.get(repositoryChainModuleSource.getRepositoryId());
        if (moduleVersionRepository == null) {
            throw new IllegalStateException("Attempting to resolve artifacts from invalid repository");
        }
        return moduleVersionRepository;
    }

}
