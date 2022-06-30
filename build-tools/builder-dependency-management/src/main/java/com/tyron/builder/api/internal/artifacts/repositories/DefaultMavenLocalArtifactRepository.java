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
package com.tyron.builder.api.internal.artifacts.repositories;

import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.parser.GradleModuleMetadataParser;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.parser.MetaDataParser;
import com.tyron.builder.api.internal.artifacts.repositories.maven.MavenMetadataLoader;
import com.tyron.builder.api.internal.artifacts.repositories.metadata.DefaultMavenPomMetadataSource;
import com.tyron.builder.api.internal.artifacts.repositories.metadata.MavenLocalPomMetadataSource;
import com.tyron.builder.api.internal.artifacts.repositories.metadata.MavenMetadataArtifactProvider;
import com.tyron.builder.api.internal.artifacts.repositories.metadata.MavenMutableModuleMetadataFactory;
import com.tyron.builder.api.internal.artifacts.repositories.resolver.ExternalResourceArtifactResolver;
import com.tyron.builder.api.internal.artifacts.repositories.resolver.MavenResolver;
import com.tyron.builder.api.internal.artifacts.repositories.transport.RepositoryTransport;
import com.tyron.builder.api.internal.artifacts.repositories.transport.RepositoryTransportFactory;
import com.tyron.builder.internal.component.external.model.ModuleComponentArtifactIdentifier;
import com.tyron.builder.internal.component.external.model.ModuleComponentArtifactMetadata;
import com.tyron.builder.internal.component.external.model.maven.MutableMavenModuleResolveMetadata;

import com.tyron.builder.api.artifacts.repositories.AuthenticationContainer;
import com.tyron.builder.api.artifacts.repositories.MavenArtifactRepository;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.internal.hash.ChecksumService;
import com.tyron.builder.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.internal.isolation.IsolatableFactory;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.resolve.result.DefaultResourceAwareResolveResult;
import com.tyron.builder.internal.resource.local.FileResourceRepository;
import com.tyron.builder.internal.resource.local.FileStore;
import com.tyron.builder.internal.resource.local.LocallyAvailableResourceFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

public class DefaultMavenLocalArtifactRepository extends DefaultMavenArtifactRepository implements MavenArtifactRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(MavenResolver.class);
    private final ChecksumService checksumService;

    public DefaultMavenLocalArtifactRepository(FileResolver fileResolver, RepositoryTransportFactory transportFactory,
                                               LocallyAvailableResourceFinder<ModuleComponentArtifactMetadata> locallyAvailableResourceFinder, InstantiatorFactory instantiatorFactory,
                                               FileStore<ModuleComponentArtifactIdentifier> artifactFileStore,
                                               MetaDataParser<MutableMavenModuleResolveMetadata> pomParser,
                                               GradleModuleMetadataParser metadataParser,
                                               AuthenticationContainer authenticationContainer,
                                               FileResourceRepository fileResourceRepository,
                                               MavenMutableModuleMetadataFactory metadataFactory,
                                               IsolatableFactory isolatableFactory,
                                               ObjectFactory objectFactory,
                                               DefaultUrlArtifactRepository.Factory urlArtifactRepositoryFactory,
                                               ChecksumService checksumService) {
        super(fileResolver, transportFactory, locallyAvailableResourceFinder, instantiatorFactory, artifactFileStore, pomParser, metadataParser, authenticationContainer, null, fileResourceRepository, metadataFactory, isolatableFactory, objectFactory, urlArtifactRepositoryFactory, checksumService, null);
        this.checksumService = checksumService;
    }

    @Override
    protected MavenResolver createRealResolver() {
        URI rootUri = validateUrl();

        RepositoryTransport transport = getTransport(rootUri.getScheme());
        MavenMetadataLoader mavenMetadataLoader = new MavenMetadataLoader(transport.getResourceAccessor(), getResourcesFileStore());
        Instantiator injector = createInjectorForMetadataSuppliers(transport, getInstantiatorFactory(), rootUri, getResourcesFileStore());
        MavenResolver resolver = new MavenResolver(
            getName(),
            rootUri,
            transport,
            getLocallyAvailableResourceFinder(),
            getArtifactFileStore(),
            createMetadataSources(mavenMetadataLoader),
            MavenMetadataArtifactProvider.INSTANCE,
            mavenMetadataLoader,
            null,
            null,
            injector,
            checksumService);
        for (URI repoUrl : getArtifactUrls()) {
            resolver.addArtifactLocation(repoUrl);
        }
        return resolver;
    }

    @Override
    protected DefaultMavenPomMetadataSource createPomMetadataSource(MavenMetadataLoader mavenMetadataLoader, FileResourceRepository fileResourceRepository) {
        return new MavenLocalPomMetadataSource(MavenMetadataArtifactProvider.INSTANCE, getPomParser(), fileResourceRepository, getMetadataValidationServices(), mavenMetadataLoader, checksumService);
    }

    @Override
    protected DefaultMavenPomMetadataSource.MavenMetadataValidator getMetadataValidationServices() {
        return new MavenLocalMetadataValidator();
    }

    /**
     * It is common for a local m2 repo to have POM files with no respective artifacts. Ignore these POM files.
     */
    private static class MavenLocalMetadataValidator implements DefaultMavenPomMetadataSource.MavenMetadataValidator {
        @Override
        public boolean isUsableModule(String repoName, MutableMavenModuleResolveMetadata metaData, ExternalResourceArtifactResolver artifactResolver) {

            if (metaData.isPomPackaging()) {
                return true;
            }

            // check custom packaging
            ModuleComponentArtifactMetadata artifact;
            if (metaData.isKnownJarPackaging()) {
                artifact = metaData.artifact("jar", "jar", null);
            } else {
                artifact = metaData.artifact(metaData.getPackaging(), metaData.getPackaging(), null);
            }

            if (artifactResolver.artifactExists(artifact, new DefaultResourceAwareResolveResult())) {
                return true;
            }

            LOGGER.debug("POM file found for module '{}' in repository '{}' but no artifact found. Ignoring.", metaData.getModuleVersionId(), repoName);
            return false;

        }
    }
}
