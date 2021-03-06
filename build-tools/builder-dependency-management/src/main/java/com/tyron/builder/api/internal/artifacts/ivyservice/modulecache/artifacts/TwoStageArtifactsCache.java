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

import com.tyron.builder.util.internal.BuildCommencedTimeProvider;

public class TwoStageArtifactsCache extends AbstractArtifactsCache {
    private final AbstractArtifactsCache readOnlyCache;
    private final AbstractArtifactsCache writableCache;

    public TwoStageArtifactsCache(BuildCommencedTimeProvider timeProvider, AbstractArtifactsCache readOnlyCache, AbstractArtifactsCache writableCache) {
        super(timeProvider);
        this.readOnlyCache = readOnlyCache;
        this.writableCache = writableCache;
    }

    @Override
    protected void store(ArtifactsAtRepositoryKey key, ModuleArtifactsCacheEntry entry) {
        writableCache.store(key, entry);
    }

    @Override
    protected ModuleArtifactsCacheEntry get(ArtifactsAtRepositoryKey key) {
        ModuleArtifactsCacheEntry entry = writableCache.get(key);
        if (entry != null) {
            return entry;
        }
        return readOnlyCache.get(key);
    }
}
