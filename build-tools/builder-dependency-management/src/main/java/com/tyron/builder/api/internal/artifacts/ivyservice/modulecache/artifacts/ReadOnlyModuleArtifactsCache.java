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
package com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.artifacts;

import com.google.common.hash.HashCode;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.internal.artifacts.ivyservice.ArtifactCacheLockingManager;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.ModuleComponentRepository;
import com.tyron.builder.internal.component.model.ComponentArtifactMetadata;
import com.tyron.builder.util.internal.BuildCommencedTimeProvider;

import java.util.Collection;

public class ReadOnlyModuleArtifactsCache extends DefaultModuleArtifactsCache {
    public ReadOnlyModuleArtifactsCache(BuildCommencedTimeProvider timeProvider,
                                        ArtifactCacheLockingManager artifactCacheLockingManager) {
        super(timeProvider, artifactCacheLockingManager);
    }

    @Override
    protected void store(ArtifactsAtRepositoryKey key, ModuleArtifactsCacheEntry entry) {
        operationShouldNotHaveBeenCalled();
    }

    @Override
    public CachedArtifacts cacheArtifacts(ModuleComponentRepository repository,
                                          ComponentIdentifier componentId,
                                          String context,
                                          HashCode descriptorHash,
                                          Collection<? extends ComponentArtifactMetadata> artifacts) {
        return operationShouldNotHaveBeenCalled();
    }

    private static <T> T operationShouldNotHaveBeenCalled() {
        throw new UnsupportedOperationException(
                "A write operation shouldn't have been called in a read-only cache");
    }

}
