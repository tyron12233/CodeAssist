/*
 * Copyright 2019 the original author or authors.
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
package com.tyron.builder.api.internal.artifacts.repositories.metadata;

import com.tyron.builder.api.internal.artifacts.repositories.resolver.ExternalResourceArtifactResolver;
import com.tyron.builder.api.internal.artifacts.repositories.resolver.ResourcePattern;
import com.tyron.builder.api.internal.artifacts.repositories.resolver.VersionLister;

import com.tyron.builder.api.artifacts.ModuleIdentifier;
import com.tyron.builder.api.artifacts.component.ModuleComponentIdentifier;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers;
import com.tyron.builder.internal.component.external.model.ModuleDependencyMetadata;
import com.tyron.builder.internal.component.external.model.MutableModuleComponentResolveMetadata;
import com.tyron.builder.internal.component.model.ComponentOverrideMetadata;
import com.tyron.builder.internal.resolve.result.BuildableModuleComponentMetaDataResolveResult;
import com.tyron.builder.internal.resolve.result.BuildableModuleVersionListingResolveResult;

import java.util.List;

/**
 * A module metadata source which is a pure performance optimization. Because today in the wild there
 * are very few Gradle metadata sources, this source will first try to get a POM file (or an Ivy file)
 * and if it finds a marker in the POM (Ivy), it will use Gradle metadata instead.
 *
 * It also means that we're going to pay a small price if Gradle metadata is present: we would fetch
 * a POM file and parse it, then fetch Gradle metadata and parse it (doing twice the work).
 */
public class RedirectingGradleMetadataModuleMetadataSource extends AbstractMetadataSource<MutableModuleComponentResolveMetadata> {
    private final MetadataSource<?> delegate;
    private final MetadataSource<MutableModuleComponentResolveMetadata> gradleModuleMetadataSource;

    public RedirectingGradleMetadataModuleMetadataSource(MetadataSource<?> delegate, MetadataSource<MutableModuleComponentResolveMetadata> gradleModuleMetadataSource) {
        this.delegate = delegate;
        this.gradleModuleMetadataSource = gradleModuleMetadataSource;
    }

    @Override
    public MutableModuleComponentResolveMetadata create(String repositoryName, ComponentResolvers componentResolvers, ModuleComponentIdentifier moduleComponentIdentifier, ComponentOverrideMetadata prescribedMetaData, ExternalResourceArtifactResolver artifactResolver, BuildableModuleComponentMetaDataResolveResult result) {
        MutableModuleComponentResolveMetadata metadata = delegate.create(repositoryName, componentResolvers, moduleComponentIdentifier, prescribedMetaData, artifactResolver, result);
        if (result.shouldUseGradleMetatada()) {
            MutableModuleComponentResolveMetadata resolveMetadata = gradleModuleMetadataSource.create(repositoryName, componentResolvers, moduleComponentIdentifier, prescribedMetaData, artifactResolver, result);
            if (resolveMetadata != null) {
                return resolveMetadata;
            }
        }
        return metadata;
    }

    @Override
    public void listModuleVersions(ModuleDependencyMetadata dependency, ModuleIdentifier module, List<ResourcePattern> ivyPatterns, List<ResourcePattern> artifactPatterns, VersionLister versionLister, BuildableModuleVersionListingResolveResult result) {
        delegate.listModuleVersions(dependency, module, ivyPatterns, artifactPatterns, versionLister, result);
    }
}
