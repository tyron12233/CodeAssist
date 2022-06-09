/*
 * Copyright 2015 the original author or authors.
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

package com.tyron.builder.internal.resource.transport;

import com.tyron.builder.api.internal.artifacts.ivyservice.ArtifactCacheLockingManager;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolutionstrategy.ExternalResourceCachePolicy;

import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.cache.internal.ProducerGuard;
import com.tyron.builder.internal.hash.ChecksumService;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.resource.ExternalResourceName;
import com.tyron.builder.internal.resource.ExternalResourceRepository;
import com.tyron.builder.internal.resource.cached.CachedExternalResourceIndex;
import com.tyron.builder.internal.resource.local.FileResourceRepository;
import com.tyron.builder.internal.resource.transfer.CacheAwareExternalResourceAccessor;
import com.tyron.builder.internal.resource.transfer.DefaultCacheAwareExternalResourceAccessor;
import com.tyron.builder.internal.resource.transfer.ExternalResourceConnector;
import com.tyron.builder.internal.resource.transfer.ProgressLoggingExternalResourceAccessor;
import com.tyron.builder.internal.resource.transfer.ProgressLoggingExternalResourceLister;
import com.tyron.builder.internal.resource.transfer.ProgressLoggingExternalResourceUploader;
import com.tyron.builder.util.internal.BuildCommencedTimeProvider;

public class ResourceConnectorRepositoryTransport extends AbstractRepositoryTransport {
    private final ExternalResourceRepository repository;
    private final DefaultCacheAwareExternalResourceAccessor resourceAccessor;

    public ResourceConnectorRepositoryTransport(String name,
                                                TemporaryFileProvider temporaryFileProvider,
                                                CachedExternalResourceIndex<String> cachedExternalResourceIndex,
                                                BuildCommencedTimeProvider timeProvider,
                                                ArtifactCacheLockingManager artifactCacheLockingManager,
                                                ExternalResourceConnector connector,
                                                BuildOperationExecutor buildOperationExecutor,
                                                ExternalResourceCachePolicy cachePolicy,
                                                ProducerGuard<ExternalResourceName> producerGuard,
                                                FileResourceRepository fileResourceRepository,
                                                ChecksumService checksumService) {
        super(name);
        ProgressLoggingExternalResourceUploader loggingUploader = new ProgressLoggingExternalResourceUploader(connector, buildOperationExecutor);
        ProgressLoggingExternalResourceAccessor loggingAccessor = new ProgressLoggingExternalResourceAccessor(connector, buildOperationExecutor);
        ProgressLoggingExternalResourceLister loggingLister = new ProgressLoggingExternalResourceLister(connector, buildOperationExecutor);
        repository = new DefaultExternalResourceRepository(name, loggingAccessor, loggingUploader, loggingLister);
        resourceAccessor = new DefaultCacheAwareExternalResourceAccessor(repository, cachedExternalResourceIndex, timeProvider, temporaryFileProvider, artifactCacheLockingManager, cachePolicy, producerGuard, fileResourceRepository, checksumService);
    }

    @Override
    public ExternalResourceRepository getRepository() {
        return repository;
    }

    @Override
    public CacheAwareExternalResourceAccessor getResourceAccessor() {
        return resourceAccessor;
    }

    @Override
    public boolean isLocal() {
        return false;
    }
}
