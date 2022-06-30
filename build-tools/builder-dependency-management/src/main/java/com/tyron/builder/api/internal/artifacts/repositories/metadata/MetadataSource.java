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
package com.tyron.builder.api.internal.artifacts.repositories.metadata;

import com.google.common.hash.Hasher;
import com.tyron.builder.api.artifacts.ModuleIdentifier;
import com.tyron.builder.api.artifacts.component.ModuleComponentIdentifier;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers;
import com.tyron.builder.api.internal.artifacts.repositories.resolver.ExternalResourceArtifactResolver;
import com.tyron.builder.api.internal.artifacts.repositories.resolver.ResourcePattern;
import com.tyron.builder.api.internal.artifacts.repositories.resolver.VersionLister;
import com.tyron.builder.internal.component.external.model.ModuleDependencyMetadata;
import com.tyron.builder.internal.component.external.model.MutableModuleComponentResolveMetadata;
import com.tyron.builder.internal.component.model.ComponentOverrideMetadata;
import com.tyron.builder.internal.resolve.result.BuildableModuleComponentMetaDataResolveResult;
import com.tyron.builder.internal.resolve.result.BuildableModuleVersionListingResolveResult;

import java.util.List;

import javax.annotation.Nullable;

/**
 * Represents a source of metadata for a repository. Each implementation is responsible for a
 * different metadata
 * format: for discovering the metadata artifact, parsing the metadata and constructing a
 * `MutableModuleComponentResolveMetadata`.
 */
public interface MetadataSource<S extends MutableModuleComponentResolveMetadata> {

    @Nullable
    S create(String repositoryName,
             ComponentResolvers componentResolvers,
             ModuleComponentIdentifier moduleComponentIdentifier,
             ComponentOverrideMetadata prescribedMetaData,
             ExternalResourceArtifactResolver artifactResolver,
// Required for MavenLocal to verify the presence of the artifact
             BuildableModuleComponentMetaDataResolveResult result);

    /**
     * Use the supplied patterns and version lister to list available versions for the supplied
     * dependency/module.
     * <p>
     * This method would encapsulates all version listing for a metadata source, supplying the
     * result (if found) to the
     * {@link BuildableModuleVersionListingResolveResult} parameter.
     * <p>
     * Ideally, the ivyPatterns + artifactPatterns + versionLister would be encapsulated into a
     * single 'module resource accessor'.
     */
    void listModuleVersions(ModuleDependencyMetadata dependency,
                            ModuleIdentifier module,
                            List<ResourcePattern> ivyPatterns,
                            List<ResourcePattern> artifactPatterns,
                            VersionLister versionLister,
                            BuildableModuleVersionListingResolveResult result);

    void appendId(Hasher hasher);
}
