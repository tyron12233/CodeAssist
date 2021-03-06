/*
 * Copyright 2020 the original author or authors.
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
package com.tyron.builder.api.internal.artifacts.ivyservice.modulecache;

import com.google.common.collect.Interner;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.result.AttributeContainerSerializer;
import com.tyron.builder.api.internal.artifacts.repositories.metadata.IvyMutableModuleMetadataFactory;
import com.tyron.builder.api.internal.artifacts.repositories.metadata.MavenMutableModuleMetadataFactory;

import com.tyron.builder.api.artifacts.component.ModuleComponentIdentifier;
import com.tyron.builder.api.internal.artifacts.ImmutableModuleIdentifierFactory;
import com.tyron.builder.api.internal.artifacts.ivyservice.ArtifactCacheLockingManager;
import com.tyron.builder.api.internal.artifacts.ivyservice.ArtifactCacheMetadata;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.ModuleComponentRepository;
import com.tyron.builder.internal.component.external.model.ModuleComponentResolveMetadata;
import com.tyron.builder.internal.hash.ChecksumService;
import com.tyron.builder.util.internal.BuildCommencedTimeProvider;

public class ReadOnlyModuleMetadataCache extends PersistentModuleMetadataCache {
    public ReadOnlyModuleMetadataCache(BuildCommencedTimeProvider timeProvider, ArtifactCacheLockingManager artifactCacheLockingManager, ArtifactCacheMetadata artifactCacheMetadata, ImmutableModuleIdentifierFactory moduleIdentifierFactory, AttributeContainerSerializer attributeContainerSerializer, MavenMutableModuleMetadataFactory mavenMetadataFactory, IvyMutableModuleMetadataFactory ivyMetadataFactory, Interner<String> stringInterner, ModuleSourcesSerializer moduleSourcesSerializer, ChecksumService checksumService) {
        super(timeProvider, artifactCacheLockingManager, artifactCacheMetadata, moduleIdentifierFactory, attributeContainerSerializer, mavenMetadataFactory, ivyMetadataFactory, stringInterner, moduleSourcesSerializer, checksumService);
    }

    @Override
    protected CachedMetadata store(ModuleComponentAtRepositoryKey key, ModuleMetadataCacheEntry entry, CachedMetadata cachedMetadata) {
        operationShouldNotHaveBeenCalled();
        return cachedMetadata;
    }

    @Override
    public CachedMetadata cacheMissing(ModuleComponentRepository repository, ModuleComponentIdentifier id) {
        return operationShouldNotHaveBeenCalled();
    }

    @Override
    public CachedMetadata cacheMetaData(ModuleComponentRepository repository, ModuleComponentIdentifier id, ModuleComponentResolveMetadata metadata) {
        return operationShouldNotHaveBeenCalled();
    }

    private static <T> T operationShouldNotHaveBeenCalled() {
        throw new UnsupportedOperationException("A write operation shouldn't have been called in a read-only cache");
    }
}
