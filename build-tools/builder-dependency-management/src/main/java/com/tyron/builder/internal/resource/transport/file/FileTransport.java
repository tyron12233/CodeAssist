/*
 * Copyright 2011 the original author or authors.
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
package com.tyron.builder.internal.resource.transport.file;

import com.tyron.builder.api.internal.artifacts.ivyservice.resolutionstrategy.DefaultExternalResourceCachePolicy;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolutionstrategy.ExternalResourceCachePolicy;
import com.tyron.builder.api.internal.artifacts.ivyservice.ArtifactCacheLockingManager;

import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.cache.internal.ProducerGuard;
import com.tyron.builder.internal.hash.ChecksumService;
import com.tyron.builder.internal.resource.ExternalResourceName;
import com.tyron.builder.internal.resource.ExternalResourceRepository;
import com.tyron.builder.internal.resource.cached.CachedExternalResourceIndex;
import com.tyron.builder.internal.resource.local.FileResourceListener;
import com.tyron.builder.internal.resource.local.FileResourceRepository;
import com.tyron.builder.internal.resource.local.LocallyAvailableExternalResource;
import com.tyron.builder.internal.resource.local.LocallyAvailableResourceCandidates;
import com.tyron.builder.internal.resource.transfer.CacheAwareExternalResourceAccessor;
import com.tyron.builder.internal.resource.transfer.DefaultCacheAwareExternalResourceAccessor;
import com.tyron.builder.internal.resource.transport.AbstractRepositoryTransport;
import com.tyron.builder.util.internal.BuildCommencedTimeProvider;

import javax.annotation.Nullable;
import java.io.IOException;

public class FileTransport extends AbstractRepositoryTransport {
    private final FileResourceRepository repository;
    private final FileCacheAwareExternalResourceAccessor resourceAccessor;

    public FileTransport(String name, FileResourceRepository repository, CachedExternalResourceIndex<String> cachedExternalResourceIndex, TemporaryFileProvider temporaryFileProvider, BuildCommencedTimeProvider timeProvider, ArtifactCacheLockingManager artifactCacheLockingManager, ProducerGuard<ExternalResourceName> producerGuard, ChecksumService checksumService, FileResourceListener listener) {
        super(name);
        this.repository = repository;
        ExternalResourceCachePolicy cachePolicy = new DefaultExternalResourceCachePolicy();
        resourceAccessor = new FileCacheAwareExternalResourceAccessor(new DefaultCacheAwareExternalResourceAccessor(repository, cachedExternalResourceIndex, timeProvider, temporaryFileProvider, artifactCacheLockingManager, cachePolicy, producerGuard, repository, checksumService), listener);
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public ExternalResourceRepository getRepository() {
        return repository;
    }

    @Override
    public CacheAwareExternalResourceAccessor getResourceAccessor() {
        return resourceAccessor;
    }

    private class FileCacheAwareExternalResourceAccessor implements CacheAwareExternalResourceAccessor {
        private final CacheAwareExternalResourceAccessor delegate;
        private final FileResourceListener listener;

        FileCacheAwareExternalResourceAccessor(CacheAwareExternalResourceAccessor delegate, FileResourceListener listener) {
            this.delegate = delegate;
            this.listener = listener;
        }

        @Nullable
        @Override
        public LocallyAvailableExternalResource getResource(ExternalResourceName source, @Nullable String baseName, ResourceFileStore fileStore, @Nullable LocallyAvailableResourceCandidates additionalCandidates) throws IOException {
            LocallyAvailableExternalResource resource = repository.resource(source);
            listener.fileObserved(resource.getFile());
            if (!resource.getFile().exists()) {
                return null;
            }
            if (baseName == null || resource.getFile().getName().equals(baseName)) {
                // Use the origin file when it can satisfy the basename requirements
                return resource;
            }

            // Use the file from the cache when it does not
            return delegate.getResource(source, baseName, fileStore, additionalCandidates);
        }
    }
}
