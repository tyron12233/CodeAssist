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

package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.internal.artifacts.ivyservice.ArtifactCachesProvider;
import com.tyron.builder.api.internal.artifacts.ivyservice.DefaultArtifactCaches;
import com.tyron.builder.api.internal.artifacts.transform.ImmutableTransformationWorkspaceServices;

import com.tyron.builder.BuildAdapter;
import com.tyron.builder.BuildResult;
import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.internal.cache.StringInterner;
import com.tyron.builder.api.internal.changedetection.state.DefaultExecutionHistoryCacheAccess;
import com.tyron.builder.cache.CacheBuilder;
import com.tyron.builder.cache.CacheRepository;
import com.tyron.builder.cache.internal.CrossBuildInMemoryCacheFactory;
import com.tyron.builder.cache.internal.InMemoryCacheDecoratorFactory;
import com.tyron.builder.cache.internal.UsedGradleVersions;
import com.tyron.builder.cache.scopes.GlobalScopedCache;
import com.tyron.builder.internal.Try;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.execution.history.ExecutionHistoryCacheAccess;
import com.tyron.builder.internal.execution.history.ExecutionHistoryStore;
import com.tyron.builder.internal.execution.history.impl.DefaultExecutionHistoryStore;
import com.tyron.builder.internal.file.FileAccessTimeJournal;

public class DependencyManagementGradleUserHomeScopeServices {

    DefaultArtifactCaches.WritableArtifactCacheLockingParameters createWritableArtifactCacheLockingParameters(FileAccessTimeJournal fileAccessTimeJournal, UsedGradleVersions usedGradleVersions) {
        return new DefaultArtifactCaches.WritableArtifactCacheLockingParameters() {
            @Override
            public FileAccessTimeJournal getFileAccessTimeJournal() {
                return fileAccessTimeJournal;
            }

            @Override
            public UsedGradleVersions getUsedGradleVersions() {
                return usedGradleVersions;
            }
        };
    }

    ArtifactCachesProvider createArtifactCaches(GlobalScopedCache globalScopedCache,
                                                CacheRepository cacheRepository,
                                                DefaultArtifactCaches.WritableArtifactCacheLockingParameters parameters,
                                                ListenerManager listenerManager,
                                                DocumentationRegistry documentationRegistry) {
        DefaultArtifactCaches artifactCachesProvider = new DefaultArtifactCaches(globalScopedCache, cacheRepository, parameters, documentationRegistry);
        listenerManager.addListener(new BuildAdapter() {
            @Override
            public void buildFinished(BuildResult result) {
                artifactCachesProvider.getWritableCacheLockingManager().useCache(() -> {
                    // forces cleanup even if cache wasn't used
                });
            }
        });
        return artifactCachesProvider;
    }

    ExecutionHistoryCacheAccess createExecutionHistoryCacheAccess(GlobalScopedCache cacheRepository) {
        return new DefaultExecutionHistoryCacheAccess(cacheRepository);
    }

    ExecutionHistoryStore createExecutionHistoryStore(
        ExecutionHistoryCacheAccess executionHistoryCacheAccess,
        InMemoryCacheDecoratorFactory inMemoryCacheDecoratorFactory,
        StringInterner stringInterner
    ) {
        return new DefaultExecutionHistoryStore(
            executionHistoryCacheAccess,
            inMemoryCacheDecoratorFactory,
            stringInterner
        );
    }

    ImmutableTransformationWorkspaceServices createTransformerWorkspaceServices(
        ArtifactCachesProvider artifactCaches,
        CacheRepository cacheRepository,
        CrossBuildInMemoryCacheFactory crossBuildInMemoryCacheFactory,
        FileAccessTimeJournal fileAccessTimeJournal,
        ExecutionHistoryStore executionHistoryStore
    ) {
        return new ImmutableTransformationWorkspaceServices(
            cacheRepository
                .cache(artifactCaches.getWritableCacheMetadata().getTransformsStoreDirectory())
                .withCrossVersionCache(CacheBuilder.LockTarget.DefaultTarget)
                .withDisplayName("Artifact transforms cache"),
            fileAccessTimeJournal,
            executionHistoryStore,
            crossBuildInMemoryCacheFactory.newCacheRetainingDataFromPreviousBuild(Try::isSuccessful)
        );
    }
}
