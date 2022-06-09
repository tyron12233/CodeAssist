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

package com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve;

import com.tyron.builder.api.internal.artifacts.repositories.resolver.MetadataFetchingCost;

import com.tyron.builder.api.artifacts.component.ModuleComponentIdentifier;
import com.tyron.builder.api.internal.component.ArtifactType;
import com.tyron.builder.internal.component.external.model.ModuleDependencyMetadata;
import com.tyron.builder.internal.component.model.ComponentArtifactMetadata;
import com.tyron.builder.internal.component.model.ComponentOverrideMetadata;
import com.tyron.builder.internal.component.model.ComponentResolveMetadata;
import com.tyron.builder.internal.component.model.ConfigurationMetadata;
import com.tyron.builder.internal.component.model.ModuleSources;
import com.tyron.builder.internal.resolve.result.BuildableArtifactResolveResult;
import com.tyron.builder.internal.resolve.result.BuildableArtifactSetResolveResult;
import com.tyron.builder.internal.resolve.result.BuildableComponentArtifactsResolveResult;
import com.tyron.builder.internal.resolve.result.BuildableModuleComponentMetaDataResolveResult;
import com.tyron.builder.internal.resolve.result.BuildableModuleVersionListingResolveResult;
import com.tyron.builder.internal.resolve.resolver.ArtifactResolver;
import com.tyron.builder.internal.resolve.resolver.ComponentMetaDataResolver;
import com.tyron.builder.internal.resolve.resolver.DependencyToComponentIdResolver;

/**
 * Provides access to a repository of components that are identified by a ModuleComponentIdentifier.
 *
 * The plan is to eventually sync this with
 * {@link DependencyToComponentIdResolver},
 * {@link ComponentMetaDataResolver} and
 * {@link ArtifactResolver}.
 */
public interface ModuleComponentRepositoryAccess {
    /**
     * Resolves the given dependency to a list of module versions.
     */
    void listModuleVersions(ModuleDependencyMetadata dependency, BuildableModuleVersionListingResolveResult result);

    /**
     * Resolves the metadata for a module component.
     */
    void resolveComponentMetaData(ModuleComponentIdentifier moduleComponentIdentifier, ComponentOverrideMetadata requestMetaData, BuildableModuleComponentMetaDataResolveResult result);

    /**
     * Resolves a set of artifacts belonging to the given component. Any failures are packaged up in the result.
     */
    void resolveArtifacts(ComponentResolveMetadata component, ConfigurationMetadata variant, BuildableComponentArtifactsResolveResult result);

    /**
     * Resolves a set of artifacts belonging to the given component, with the type specified. Any failures are packaged up in the result.
     */
    void resolveArtifactsWithType(ComponentResolveMetadata component, ArtifactType artifactType, BuildableArtifactSetResolveResult result);

    /**
     * Resolves the given artifact. Any failures are packaged up in the result.
     */
    void resolveArtifact(ComponentArtifactMetadata artifact, ModuleSources moduleSources, BuildableArtifactResolveResult result);

    MetadataFetchingCost estimateMetadataFetchingCost(ModuleComponentIdentifier moduleComponentIdentifier);
}
