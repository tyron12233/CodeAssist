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
package com.tyron.builder.api.internal.artifacts.repositories.resolver;

import com.tyron.builder.api.artifacts.component.ModuleComponentIdentifier;
import com.tyron.builder.internal.component.external.model.ModuleComponentArtifactIdentifier;
import com.tyron.builder.internal.component.external.model.ModuleComponentArtifactMetadata;
import com.tyron.builder.internal.component.external.model.UrlBackedArtifactMetadata;
import com.tyron.builder.internal.component.model.ModuleDescriptorArtifactMetadata;
import com.tyron.builder.internal.resolve.result.ResourceAwareResolveResult;
import com.tyron.builder.internal.resource.ExternalResourceName;
import com.tyron.builder.internal.resource.ExternalResourceRepository;
import com.tyron.builder.internal.resource.ResourceExceptions;
import com.tyron.builder.internal.resource.local.FileStore;
import com.tyron.builder.internal.resource.local.LocallyAvailableExternalResource;
import com.tyron.builder.internal.resource.local.LocallyAvailableResourceCandidates;
import com.tyron.builder.internal.resource.local.LocallyAvailableResourceFinder;
import com.tyron.builder.internal.resource.transfer.CacheAwareExternalResourceAccessor;
import com.tyron.builder.internal.resource.transfer.CacheAwareExternalResourceAccessor.DefaultResourceFileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

class DefaultExternalResourceArtifactResolver implements ExternalResourceArtifactResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultExternalResourceArtifactResolver.class);

    private final ExternalResourceRepository repository;
    private final LocallyAvailableResourceFinder<ModuleComponentArtifactMetadata> locallyAvailableResourceFinder;
    private final List<ResourcePattern> ivyPatterns;
    private final List<ResourcePattern> artifactPatterns;
    private final FileStore<ModuleComponentArtifactIdentifier> fileStore;
    private final CacheAwareExternalResourceAccessor resourceAccessor;

    public DefaultExternalResourceArtifactResolver(ExternalResourceRepository repository, LocallyAvailableResourceFinder<ModuleComponentArtifactMetadata> locallyAvailableResourceFinder, List<ResourcePattern> ivyPatterns, List<ResourcePattern> artifactPatterns, FileStore<ModuleComponentArtifactIdentifier> fileStore, CacheAwareExternalResourceAccessor resourceAccessor) {
        this.repository = repository;
        this.locallyAvailableResourceFinder = locallyAvailableResourceFinder;
        this.ivyPatterns = ivyPatterns;
        this.artifactPatterns = artifactPatterns;
        this.fileStore = fileStore;
        this.resourceAccessor = resourceAccessor;
    }

    @Override
    public LocallyAvailableExternalResource resolveArtifact(ModuleComponentArtifactMetadata artifact, ResourceAwareResolveResult result) {
        if (artifact instanceof ModuleDescriptorArtifactMetadata) {
            return downloadStaticResource(ivyPatterns, artifact, result);
        }
        return downloadStaticResource(artifactPatterns, artifact, result);
    }

    @Override
    public boolean artifactExists(ModuleComponentArtifactMetadata artifact, ResourceAwareResolveResult result) {
        return staticResourceExists(artifactPatterns, artifact, result);
    }

    private boolean staticResourceExists(List<ResourcePattern> patternList, ModuleComponentArtifactMetadata artifact, ResourceAwareResolveResult result) {
        for (ResourcePattern resourcePattern : patternList) {
            if (isIncomplete(resourcePattern, artifact)) {
                continue;
            }
            ExternalResourceName location = resourcePattern.getLocation(artifact);
            result.attempted(location);
            LOGGER.debug("Loading {}", location);
            try {
                if (repository.resource(location, true).getMetaData() != null) {
                    return true;
                }
            } catch (Exception e) {
                throw ResourceExceptions.getFailed(location.getUri(), e);
            }
        }
        return false;
    }

    private LocallyAvailableExternalResource downloadStaticResource(List<ResourcePattern> patternList, final ModuleComponentArtifactMetadata artifact, ResourceAwareResolveResult result) {
        if (artifact instanceof UrlBackedArtifactMetadata) {
            UrlBackedArtifactMetadata urlArtifact = (UrlBackedArtifactMetadata) artifact;
            return downloadByUrl(patternList, urlArtifact, result);
        } else {
            return downloadByCoords(patternList, artifact, result);
        }
    }

    private LocallyAvailableExternalResource downloadByUrl(List<ResourcePattern> patternList, final UrlBackedArtifactMetadata artifact, ResourceAwareResolveResult result) {
        for (ResourcePattern resourcePattern : patternList) {
            if (isIncomplete(resourcePattern, artifact)) {
                continue;
            }
            ExternalResourceName moduleDir = resourcePattern.toModuleVersionPath(normalizeComponentId(artifact));
            ExternalResourceName location = moduleDir.resolve(artifact.getRelativeUrl());
            result.attempted(location);
            LOGGER.debug("Loading {}", location);
            LocallyAvailableResourceCandidates localCandidates = locallyAvailableResourceFinder.findCandidates(artifact);
            try {
                LocallyAvailableExternalResource resource = resourceAccessor.getResource(location, artifact.getId().getFileName(), getFileStore(artifact), localCandidates);
                if (resource != null) {
                    return resource;
                }
            } catch (Exception e) {
                throw ResourceExceptions.getFailed(location.getUri(), e);
            }
        }
        return null;
    }

    private ModuleComponentIdentifier normalizeComponentId(UrlBackedArtifactMetadata artifact) {
        ModuleComponentIdentifier rawId = artifact.getComponentId();
        if (rawId instanceof MavenUniqueSnapshotComponentIdentifier) {
            // We cannot use a Maven unique snapshot id for the path part
            return ((MavenUniqueSnapshotComponentIdentifier) rawId).getSnapshotComponent();
        }
        return rawId;
    }

    private LocallyAvailableExternalResource downloadByCoords(List<ResourcePattern> patternList, final ModuleComponentArtifactMetadata artifact, ResourceAwareResolveResult result) {
        for (ResourcePattern resourcePattern : patternList) {
            if (isIncomplete(resourcePattern, artifact)) {
                continue;
            }
            ExternalResourceName location = resourcePattern.getLocation(artifact);
            result.attempted(location);
            LOGGER.debug("Loading {}", location);
            LocallyAvailableResourceCandidates localCandidates = locallyAvailableResourceFinder.findCandidates(artifact);
            try {
                LocallyAvailableExternalResource resource = resourceAccessor.getResource(location, null, getFileStore(artifact), localCandidates);
                if (resource != null) {
                    return resource;
                }
            } catch (Exception e) {
                throw ResourceExceptions.getFailed(location.getUri(), e);
            }
        }
        return null;
    }

    private CacheAwareExternalResourceAccessor.ResourceFileStore getFileStore(final ModuleComponentArtifactMetadata artifact) {
        return new DefaultResourceFileStore<ModuleComponentArtifactIdentifier>(fileStore) {
            @Override
            protected ModuleComponentArtifactIdentifier computeKey() {
                return artifact.getId();
            }
        };
    }

    private boolean isIncomplete(ResourcePattern resourcePattern, ModuleComponentArtifactMetadata artifact) {
        return !resourcePattern.isComplete(artifact);
    }
}
